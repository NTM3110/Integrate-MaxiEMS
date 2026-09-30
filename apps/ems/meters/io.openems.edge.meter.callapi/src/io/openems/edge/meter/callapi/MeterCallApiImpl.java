package io.openems.edge.meter.callapi;

import java.io.IOException;
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
import io.openems.common.types.MeterType;
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
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException; // Để bắt lỗi timeout kết nối
import java.time.Duration; // Dòng bạn đang thiếu
import java.util.concurrent.TimeUnit;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.OneController", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class MeterCallApiImpl extends AbstractOpenemsComponent
		implements MeterCallApi, SinglePhaseMeter, ElectricityMeter, OpenemsComponent, EventHandler, TimedataProvider {

	private final Logger log = LoggerFactory.getLogger(MeterCallApiImpl.class);

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

	public MeterCallApiImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				SinglePhaseMeter.ChannelId.values(), //
				MeterCallApi.ChannelId.values() //
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
						this.channel(MeterCallApi.ChannelId.API_COMMUNICATION_FAILED).setNextValue(false);
					} else {
						this.logWarn(this.log, "No usable metric values found for: " + this.config.meterId());
						this.setApiCommunicationFailed();
					}
					return;
				}
			} else {
				this.logWarn(this.log, "No metrics found for: " + this.config.meterId());
			}
			this.safeSetApiCommunicationFailed();

		} catch (RuntimeException e) {
			this.safeSetApiCommunicationFailed();
			this.logError(this.log, "Kết nối API quá hạn (Timeout): " + e.getMessage());
		} catch (InterruptedException e) {
			this.safeSetApiCommunicationFailed();
			this.logError(this.log, "Lỗi kết nối hoặc luồng bị ngắt: " + e.getMessage());
			if (e instanceof InterruptedException) {
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
		return "http://" + this.config.ip().trim() + "/api/ui/devices/" + this.config.meterId().trim();
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
		this.channel(MeterCallApi.ChannelId.API_COMMUNICATION_FAILED).setNextValue(true);
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
		return this.config.type();
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
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.calculateEnergy();
		}
	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
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

	@Override
	public SinglePhase getPhase() {
		return SinglePhase.L1;
	}
}
