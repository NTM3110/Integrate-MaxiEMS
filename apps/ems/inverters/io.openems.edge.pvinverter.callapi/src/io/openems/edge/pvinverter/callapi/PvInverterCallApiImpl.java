package io.openems.edge.pvinverter.callapi;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.request.QueryHistoricTimeseriesDataRequest;
import io.openems.common.timedata.Resolution;
import io.openems.common.types.MeterType;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.JsonUtils;
import io.openems.common.utils.ThreadPoolUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.SinglePhaseMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException; // Để bắt lỗi timeout kết nối
import java.time.Duration; // Dòng bạn đang thiếu

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PvInverter.CallApi", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterCallApiImpl extends AbstractOpenemsComponent
		implements PvInverterCallApi, SinglePhaseMeter, ElectricityMeter, ManagedSymmetricPvInverter, OpenemsComponent,
		EventHandler, TimedataProvider {

	private static final long API_STALE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

	private final Logger log = LoggerFactory.getLogger(PvInverterCallApiImpl.class);

	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);
	private final CalculateEnergyFromPower calculateConsumptionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	private Config config;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final Object energyProductionTodayLock = new Object();
	private LocalDate energyProductionDay = null;
	private long lastEnergyCalculationTimeMs = -1;
	private double energyProductionTodayWh = 0;
	private volatile long lastApiCallStartedAtMs = -1;
	private volatile long lastSuccessfulApiCallAtMs = -1;
	private boolean staleApiCommunicationLogged = false;

	public PvInverterCallApiImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				SinglePhaseMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterCallApi.ChannelId.values() //
		);

		SinglePhaseMeter.calculateSinglePhaseFromActivePower(this);
		SinglePhaseMeter.calculateSinglePhaseFromReactivePower(this);
		SinglePhaseMeter.calculateSinglePhaseFromCurrent(this);
		SinglePhaseMeter.calculateSinglePhaseFromVoltage(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		super.activate(context, config.id(), config.alias(), config.enabled());

		if (config.enabled()) {
			this.executor.scheduleWithFixedDelay(this.apiCallTask, 0, 5, TimeUnit.SECONDS); // Call API every 5 seconds
			this.restoreEnergyProductionTodayFromTimedata();
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
		super.deactivate();
	}

	// 1. Khởi tạo Client với Timeout (Nên để ở cấp class, không tạo mới mỗi lần gọi)
	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)) // Timeout kết nối 5 giây
			.build();

	public final Runnable apiCallTask = () -> {
		try {
			this.lastApiCallStartedAtMs = System.currentTimeMillis();
			final var requestUrl = this.buildRequestUrl();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(requestUrl))
					.timeout(Duration.ofSeconds(10)) // Timeout nhận phản hồi 10 giây
					.GET()
					.build();

			final CompletableFuture<HttpResponse<String>> responseFuture = this.client.sendAsync(request,
					HttpResponse.BodyHandlers.ofString());
			final HttpResponse<String> response;
			try {
				response = responseFuture.get(12, TimeUnit.SECONDS);
			} catch (Exception e) {
				responseFuture.cancel(true);
				throw e;
			}

			if (response.statusCode() != 200) {
				this.logError(this.log, "API Call failed. Status: " + response.statusCode());
				this.setApiCommunicationFailed();
				return;
			}

			String responseBody = response.body();
			if (responseBody == null || responseBody.isEmpty()) {
				this.setApiCommunicationFailed();
				return;
			}

			// 3. Parse JSON an toàn
			JsonObject device = JsonUtils.parseToJsonObject(responseBody);

			// Kiểm tra null và kiểu dữ liệu trước khi lấy
			if (device.has("metrics") && !device.get("metrics").isJsonNull()) {
				JsonObject metricsObj = device.getAsJsonObject("metrics");

				if (metricsObj.has("metrics") && metricsObj.get("metrics").isJsonArray()) {
					JsonArray metricsArr = metricsObj.getAsJsonArray("metrics");
					if (this.parseMetrics(metricsArr)) {
						this.lastSuccessfulApiCallAtMs = System.currentTimeMillis();
						this.staleApiCommunicationLogged = false;
						this.channel(PvInverterCallApi.ChannelId.API_COMMUNICATION_FAILED).setNextValue(false);
					} else {
						this.logWarn(this.log, "No usable metric values found for: " + this.config.pvInverterId());
						this.setApiCommunicationFailed();
					}
					return;
				}
			} else {
				this.logWarn(this.log, "No metrics found for: " + this.config.pvInverterId());
			}
			this.safeSetApiCommunicationFailed();

		} catch (InterruptedException e) {
			this.safeSetApiCommunicationFailed();
			this.logError(this.log, "Luồng gọi API bị ngắt: " + e.getMessage());
			if (this.executor.isShutdown()) {
				Thread.currentThread().interrupt();
			}
		} catch (Exception e) {
			this.safeSetApiCommunicationFailed();
			this.logError(this.log, "Lỗi không xác định: " + e.getMessage());
		} catch (Throwable t) {
			this.safeSetApiCommunicationFailed();
			this.logError(this.log, "Fatal error in API polling task: " + t.getMessage());
		}
	};

	private String buildRequestUrl() {
		return "http://" + this.config.ip().trim() + "/api/ui/devices/" + this.config.pvInverterId().trim();
	}

	private boolean parseMetrics(JsonArray metricsArr) throws OpenemsNamedException {
		boolean hasUsableValue = false;
		for (JsonElement metricElement : metricsArr) {
			JsonObject metric = metricElement.getAsJsonObject();
			String uri = JsonUtils.getAsString(metric, "uri");
			
			if (!metric.has("value")) {
				continue;
			}
			
			String valueStr = JsonUtils.getAsString(metric, "value");
			if (valueStr == null || valueStr.isEmpty()) {
				continue;
			}
			
			try {
				switch (uri) {
				case "activePower":
				case "acActivePower":
					this._setActivePower(Math.round(Float.parseFloat(valueStr)) * (this.config.invert() ? -1 : 1));
					hasUsableValue = true;
					break;
				case "reactivePower":
				case "acReactivePower":
					this._setReactivePower(Math.round(Float.parseFloat(valueStr)) * (this.config.invert() ? -1 : 1));
					hasUsableValue = true;
					break;
				case "voltage":
					// Convert V to mV
					this._setVoltage(Math.round(Float.parseFloat(valueStr) * 1000f));
					hasUsableValue = true;
					break;
				case "current":
					// Convert A to mA
					this._setCurrent(Math.round(Float.parseFloat(valueStr) * 1000f));
					hasUsableValue = true;
					break;
				case "frequency":
					// Convert Hz to mHz
					this._setFrequency(Math.round(Float.parseFloat(valueStr) * 1000f));
					hasUsableValue = true;
					break;
				case "activeEnergyImported":
					this._setActiveConsumptionEnergy(Math.round(Double.parseDouble(valueStr)) * (this.config.invert() ? -1 : 1));
					hasUsableValue = true;
					break;
				case "activeEnergyExported":
					this._setActiveProductionEnergy(Math.round(Double.parseDouble(valueStr)) * (this.config.invert() ? -1 : 1));
					hasUsableValue = true;
					break;
				}
			} catch (NumberFormatException e) {
				this.logWarn(this.log, "Failed to parse value for " + uri + ": " + valueStr);
			}
		}
		return hasUsableValue;
	}

	private void setApiCommunicationFailed() {
		this.channel(PvInverterCallApi.ChannelId.API_COMMUNICATION_FAILED).setNextValue(true);
		this._setActivePower((Integer) null);
		this._setReactivePower((Integer) null);
		this._setVoltage((Integer) null);
		this._setCurrent((Integer) null);
		this._setFrequency((Integer) null);
		this._setActiveConsumptionEnergy((Long) null);
		this._setActiveProductionEnergy((Long) null);
	}

	private void safeSetApiCommunicationFailed() {
		try {
			this.setApiCommunicationFailed();
		} catch (Throwable t) {
			this.logError(this.log, "Unable to mark API communication failed: " + t.getMessage());
		}
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> {
			this.detectStaleApiCommunication();
			this.calculateEnergy();
		}
		}
	}

	private void detectStaleApiCommunication() {
		final var lastActivityAtMs = Math.max(this.lastApiCallStartedAtMs, this.lastSuccessfulApiCallAtMs);
		if (lastActivityAtMs <= 0) {
			return;
		}

		final var staleForMs = System.currentTimeMillis() - lastActivityAtMs;
		if (staleForMs <= API_STALE_TIMEOUT_MS) {
			return;
		}

		this.setApiCommunicationFailed();
		if (!this.staleApiCommunicationLogged) {
			this.logWarn(this.log, "No API polling activity for " + staleForMs + " ms. Marking API communication failed.");
			this.staleApiCommunicationLogged = true;
		}
	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		this.calculateEnergyProductionToday();

		// Calculate Energy
		final var activePower = this.getActivePower().get();
		if (activePower == null) {
			this.calculateProductionEnergy.update(null);
			this.calculateConsumptionEnergy.update(null);
		} else if (activePower >= 0) {
			this.calculateProductionEnergy.update(activePower);
			this.calculateConsumptionEnergy.update(0);
		} else {
			this.calculateProductionEnergy.update(0);
			this.calculateConsumptionEnergy.update(-activePower);
		}
	}

	private void restoreEnergyProductionTodayFromTimedata() {
		final var timedata = this.timedata;
		if (timedata == null) {
			return;
		}

		CompletableFuture.runAsync(() -> {
			final var now = ZonedDateTime.now();
			final var startOfDay = now.toLocalDate().atStartOfDay(now.getZone());
			final var address = new ChannelAddress(this.id(), PvInverterCallApi.ChannelId.ENERGY_PRODUCTION_TODAY.id());
			final var request = new QueryHistoricTimeseriesDataRequest(startOfDay, now, //
					Optional.of(new Resolution(5, ChronoUnit.SECONDS)), Set.of(address));

			try {
				final var result = timedata.queryHistoricData(null, request);
				if (result == null || result.isEmpty()) {
					return;
				}

				Integer restoredValue = null;
				for (var values : result.values()) {
					final var value = values.get(address);
					final var parsedValue = parseEnergyProductionTodayValue(value);
					if (parsedValue != null) {
						restoredValue = parsedValue;
					}
				}

				if (restoredValue == null) {
					return;
				}

				synchronized (this.energyProductionTodayLock) {
					this.energyProductionDay = LocalDate.now();
					this.energyProductionTodayWh = Math.max(this.energyProductionTodayWh, restoredValue);
					this.lastEnergyCalculationTimeMs = System.currentTimeMillis();
					this.channel(PvInverterCallApi.ChannelId.ENERGY_PRODUCTION_TODAY)
							.setNextValue((int) Math.max(0, Math.round(this.energyProductionTodayWh)));
				}
				this.logInfo(this.log, "Restored EnergyProductionToday from Timedata: " + restoredValue + " Wh");
			} catch (Exception e) {
				this.logWarn(this.log, "Unable to restore EnergyProductionToday from Timedata: " + e.getMessage());
			}
		});
	}

	private static Integer parseEnergyProductionTodayValue(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return null;
		}

		try {
			if (value.isJsonPrimitive()) {
				final var primitive = value.getAsJsonPrimitive();
				if (primitive.isNumber()) {
					return (int) Math.max(0, Math.round(primitive.getAsDouble()));
				}
				if (primitive.isString() && !primitive.getAsString().isBlank()) {
					return (int) Math.max(0, Math.round(Double.parseDouble(primitive.getAsString())));
				}
			}
		} catch (NumberFormatException e) {
			return null;
		}
		return null;
	}

	/**
	 * Calculates daily energy production from ActivePower [W].
	 * Result is exposed on channel ENERGY_PRODUCTION_TODAY [Wh].
	 */
	private void calculateEnergyProductionToday() {
		final var activePower = this.getActivePower().get();
		final var now = System.currentTimeMillis();
		final var currentDay = LocalDate.now();

		synchronized (this.energyProductionTodayLock) {
			if (this.energyProductionDay == null || !this.energyProductionDay.isEqual(currentDay)) {
				this.energyProductionDay = currentDay;
				this.energyProductionTodayWh = 0;
				this.lastEnergyCalculationTimeMs = now;
				this.channel(PvInverterCallApi.ChannelId.ENERGY_PRODUCTION_TODAY).setNextValue(0);
				return;
			}

			if (this.lastEnergyCalculationTimeMs > 0 && activePower != null && activePower > 0) {
				final double deltaHours = (now - this.lastEnergyCalculationTimeMs) / 3_600_000d;
				this.energyProductionTodayWh += activePower * deltaHours;
			}
			this.lastEnergyCalculationTimeMs = now;

			this.channel(PvInverterCallApi.ChannelId.ENERGY_PRODUCTION_TODAY)
					.setNextValue((int) Math.max(0, Math.round(this.energyProductionTodayWh)));
		}
	}

	@Override
	public SinglePhase getPhase() {
		return SinglePhase.L1;
	}
}
