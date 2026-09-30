package io.openems.edge.meter.elster.a1700;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.iec6205621.Iec6205621Bridge;
import io.openems.edge.bridge.iec6205621.Iec6205621Component;
import io.openems.edge.bridge.iec6205621.Iec6205621Protocol;
import io.openems.edge.bridge.iec6205621.Iec6205621Transport;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.elster.a1700.protocol.A1700Client;
import io.openems.edge.meter.elster.a1700.protocol.A1700Data;
import io.openems.edge.meter.elster.a1700.protocol.A1700Decoder;
import io.openems.edge.meter.elster.a1700.protocol.A1700Protocol;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Meter.Elster.A1700", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ElsterA1700MeterImpl extends AbstractOpenemsComponent
		implements ElsterA1700Meter, ElectricityMeter, OpenemsComponent, ComponentJsonApi, Iec6205621Component {

	public static final String READ_CURRENT = "readA1700Current";
	public static final String READ_INSTANTANEOUS = "readA1700Instantaneous";
	public static final String READ_HISTORICAL = "readA1700Historical";
	public static final String READ_PROFILE = "readA1700Profile";

	/** Number of meter-local calendar days to request, ending with today. */
	public static String profileSelectorForDates(LocalDate fromDate, LocalDate toDate, LocalDate today) {
		if (fromDate == null || toDate == null || today == null) {
			throw new IllegalArgumentException("Profile dates and today's meter-local date are required");
		}
		if (fromDate.isAfter(toDate)) {
			throw new IllegalArgumentException("fromDate must not be after toDate");
		}
		if (toDate.isAfter(today)) {
			throw new IllegalArgumentException("Profile date must not be in the future");
		}
		long days = ChronoUnit.DAYS.between(fromDate, today) + 1;
		if (days > 255) {
			throw new IllegalArgumentException("Profile date is more than 255 days old; selector encoding beyond one byte is unverified");
		}
		return String.format(java.util.Locale.ROOT, "%02X00", days);
	}

	private final Logger log = LoggerFactory.getLogger(ElsterA1700MeterImpl.class);
	private volatile Iec6205621Bridge bridge;
	private volatile Config config;
	private volatile MeterType meterType = MeterType.GRID;
	private volatile byte[] activeKey = new byte[0];
	private volatile Iec6205621Protocol protocol;
	private volatile boolean componentActive;

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.MANDATORY)
	protected synchronized void setBridge(Iec6205621Bridge bridge) {
		this.bridge = bridge;
		this.registerProtocol();
	}

	protected synchronized void unsetBridge(Iec6205621Bridge bridge) {
		if (this.bridge == bridge) {
			this.unregisterProtocol();
			this.bridge = null;
		}
	}

	public ElsterA1700MeterImpl() {
		super(OpenemsComponent.ChannelId.values(), ElectricityMeter.ChannelId.values(),
				ElsterA1700Meter.ChannelId.values());
	}

	@Activate
	private synchronized void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		this.componentActive = true;
		if (!OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "bridge", config.bridge_id())) {
			this.registerProtocol();
		}
	}

	@Modified
	private synchronized void modified(ComponentContext context, Config config) {
		this.unregisterProtocol();
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		if (!OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "bridge", config.bridge_id())) {
			this.registerProtocol();
		}
	}

	private void applyConfig(Config config) {
		validatePassword("Password 1", config.password1());
		validatePassword("Password 2", config.password2());
		validatePassword("Password 3", config.password3());
		var selected = switch (config.activePasswordLevel()) {
		case LEVEL_1 -> config.password1();
		case LEVEL_2 -> config.password2();
		case LEVEL_3 -> config.password3();
		};
		var oldKey = this.activeKey;
		this.activeKey = selected.getBytes(StandardCharsets.US_ASCII);
		Arrays.fill(oldKey, (byte) 0);
		this.config = config;
		this.meterType = config.type();
	}

	public static void validatePassword(String label, String password) {
		if (password == null || password.length() != 8
				|| !StandardCharsets.US_ASCII.newEncoder().canEncode(password)) {
			throw new IllegalArgumentException(label + " must contain exactly 8 ASCII characters");
		}
	}

	private synchronized void registerProtocol() {
		var currentBridge = this.bridge;
		if (!this.componentActive || !this.isEnabled() || currentBridge == null || this.config == null) {
			return;
		}

		var tasks = new ArrayList<A1700ReadTask>();
		if (this.config.pollingIntervalSeconds() > 0) {
			long intervalMillis = TimeUnit.SECONDS.toMillis(this.config.pollingIntervalSeconds());
			tasks.add(new A1700ReadTask("current+instantaneous", Priority.HIGH,
					System.currentTimeMillis() + 1000, finished -> finished + intervalMillis, transport -> {
						this.applyDecoded("current", this.capture(transport, "current", null));
						this.applyDecoded("instantaneous", this.capture(transport, "instantaneous", null));
					}, this::applyFailure));
		}
		if (this.config.autoReadProfile()) {
			tasks.add(new A1700ReadTask("load-profile", Priority.LOW, nextProfileExecution(),
					finished -> nextProfileExecution(), transport -> this.applyDecoded("load-profile",
							this.capture(transport, "load-profile", this.config.profileSelector())), this::applyFailure));
		}
		if (this.config.autoReadHistorical()) {
			tasks.add(new A1700ReadTask("historical", Priority.LOW, nextHistoricalExecution(),
					finished -> nextHistoricalExecution(),
					transport -> this.applyDecoded("historical", this.capture(transport, "historical", null)),
					this::applyFailure));
		}

		this.protocol = new Iec6205621Protocol(this, tasks.toArray(A1700ReadTask[]::new));
		currentBridge.addProtocol(this.id(), this.protocol);
	}

	private synchronized void unregisterProtocol() {
		var currentBridge = this.bridge;
		if (currentBridge != null && this.protocol != null) {
			currentBridge.removeProtocol(this.id());
		}
		this.protocol = null;
	}

	private static long nextProfileExecution() {
		var now = LocalDateTime.now();
		LocalDateTime next;
		if (now.getMinute() < 5) {
			next = now.withMinute(5).withSecond(0).withNano(0);
		} else if (now.getMinute() < 35) {
			next = now.withMinute(35).withSecond(0).withNano(0);
		} else {
			next = now.plusHours(1).withMinute(5).withSecond(0).withNano(0);
		}
		return next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static long nextHistoricalExecution() {
		var now = LocalDateTime.now();
		var next = now.toLocalDate().atTime(0, 10);
		if (!next.isAfter(now)) {
			next = next.plusDays(1);
		}
		return next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private CompletableFuture<Map<String, Object>> read(String group, String selector) {
		var currentBridge = this.bridge;
		if (currentBridge == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("IEC 62056-21 bridge is unavailable"));
		}
		var future = currentBridge.execute(this.id(), transport -> this.capture(transport, group, selector));
		future.whenComplete((result, error) -> {
			if (error != null) {
				this.applyFailure(error);
			}
		});
		return future;
	}

	private Map<String, Object> capture(Iec6205621Transport transport, String group, String selector) {
		var key = this.activeKey.clone();
		try {
			var capture = new A1700Client(transport, this.clientConfig(selector), this.observer()).capture(group, key);
			return A1700Decoder.decode(capture);
		} finally {
			Arrays.fill(key, (byte) 0);
		}
	}

	private A1700Client.Config clientConfig(String selector) {
		var result = new A1700Client.Config();
		result.address = this.config.outstation();
		result.expectedSerial = this.config.expectedSerialNumber();
		result.timeoutMs = this.config.timeoutMs();
		result.delayMs = this.config.exchangeDelayMs();
		result.signOnAttempts = this.config.signOnAttempts();
		result.pollTimeoutMs = this.config.instantaneousPollTimeoutMs();
		result.maxProfileBlocks = this.config.maxProfileBlocks();
		result.profileSelector = selector == null ? this.config.profileSelector() : selector;
		return result;
	}

	private A1700Client.Observer observer() {
		return new A1700Client.Observer() {
			@Override
			public void wire(boolean tx, byte[] data, boolean partial) {
				if (config.wireLogMode() == WireLogMode.OFF || !log.isDebugEnabled()) {
					return;
				}
				var rendered = A1700Protocol.render(data);
				if (config.wireLogMode() == WireLogMode.REDACTED
						&& (rendered.contains("P0") || rendered.contains("P2"))) {
					rendered = "[P0/P2 REDACTED]";
				}
				log.debug("A1700 {}{} {}", tx ? "TX" : "RX", partial ? " partial" : "", rendered);
			}

			@Override
			public void event(String message) {
				log.debug("A1700: {}", message);
			}
		};
	}

	@SuppressWarnings("unchecked")
	private void applyDecoded(String group, Map<String, Object> decoded) {
		var json = A1700Data.prettyJson(decoded);
		switch (group) {
		case "current" -> {
			this.channel(ElsterA1700Meter.ChannelId.LAST_CURRENT_JSON).setNextValue(json);
			var data = (Map<String, Object>) decoded.get("data");
			var totals = data == null ? List.of() : (List<Object>) data.get("totals");
			if (totals != null && totals.size() >= 2) {
				this.setLong(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY,
						((Map<String, Object>) totals.get(0)).get("value"), BigDecimal.ONE);
				this.setLong(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
						((Map<String, Object>) totals.get(1)).get("value"), BigDecimal.ONE);
			}
		}
		case "instantaneous" -> {
			this.channel(ElsterA1700Meter.ChannelId.LAST_INSTANTANEOUS_JSON).setNextValue(json);
			this.applyInstantaneous((Map<String, Object>) decoded.get("data"));
		}
		case "historical" -> this.channel(ElsterA1700Meter.ChannelId.LAST_HISTORICAL_JSON).setNextValue(json);
		case "load-profile" -> this.channel(ElsterA1700Meter.ChannelId.LAST_PROFILE_JSON).setNextValue(json);
		default -> throw new IllegalArgumentException("Unsupported group " + group);
		}
		var transfer = String.valueOf(decoded.get("transferStatus"));
		var decodedData = decoded.get("data") instanceof Map<?, ?> map ? map : Map.of();
		var decodeStatus = String.valueOf(decodedData.get("decodeStatus"));
		this.channel(ElsterA1700Meter.ChannelId.PARTIAL_DECODE)
				.setNextValue(!"complete".equals(transfer) || decodeStatus.startsWith("partial"));
		if (decoded.get("sessionError") == null) {
			this.channel(ElsterA1700Meter.ChannelId.COMMUNICATION_FAILED).setNextValue(false);
			this.channel(ElsterA1700Meter.ChannelId.AUTHENTICATION_FAILED).setNextValue(false);
			this.channel(ElsterA1700Meter.ChannelId.WRONG_METER).setNextValue(false);
			this.channel(ElsterA1700Meter.ChannelId.LAST_ERROR).setNextValue(null);
			this.channel(ElsterA1700Meter.ChannelId.LAST_SUCCESSFUL_READ).setNextValue(OffsetDateTime.now().toString());
		} else {
			this.applyFailure(new IllegalStateException(String.valueOf(decoded.get("sessionError"))));
		}
	}

	@SuppressWarnings("unchecked")
	private void applyInstantaneous(Map<String, Object> data) {
		if (data == null || !(data.get("groups") instanceof List<?> groups)) {
			return;
		}
		for (Object object : groups) {
			var group = (Map<String, Object>) object;
			var name = String.valueOf(group.get("group"));
			if (!(group.get("values") instanceof List<?> values)) {
				continue;
			}
			for (Object valueObject : values) {
				var value = (Map<String, Object>) valueObject;
				var phase = String.valueOf(value.get("phase"));
				var number = value.get("value");
				if (number == null) {
					continue;
				}
				var channel = instantaneousChannel(name, phase);
				if (channel == null) {
					continue;
				}
				var multiplier = switch (name) {
				case "voltage", "current", "frequency", "active_power", "reactive_power", "apparent_power" ->
					new BigDecimal("1000");
				default -> BigDecimal.ONE;
				};
				this.channel(channel).setNextValue(new BigDecimal(number.toString()).multiply(multiplier).intValue());
			}
		}
	}

	private static io.openems.edge.common.channel.ChannelId instantaneousChannel(String group, String phase) {
		return switch (group + ":" + phase) {
		case "active_power:A" -> ElectricityMeter.ChannelId.ACTIVE_POWER_L1;
		case "active_power:B" -> ElectricityMeter.ChannelId.ACTIVE_POWER_L2;
		case "active_power:C" -> ElectricityMeter.ChannelId.ACTIVE_POWER_L3;
		case "active_power:total" -> ElectricityMeter.ChannelId.ACTIVE_POWER;
		case "reactive_power:A" -> ElectricityMeter.ChannelId.REACTIVE_POWER_L1;
		case "reactive_power:B" -> ElectricityMeter.ChannelId.REACTIVE_POWER_L2;
		case "reactive_power:C" -> ElectricityMeter.ChannelId.REACTIVE_POWER_L3;
		case "reactive_power:total" -> ElectricityMeter.ChannelId.REACTIVE_POWER;
		case "voltage:A" -> ElectricityMeter.ChannelId.VOLTAGE_L1;
		case "voltage:B" -> ElectricityMeter.ChannelId.VOLTAGE_L2;
		case "voltage:C" -> ElectricityMeter.ChannelId.VOLTAGE_L3;
		case "current:A" -> ElectricityMeter.ChannelId.CURRENT_L1;
		case "current:B" -> ElectricityMeter.ChannelId.CURRENT_L2;
		case "current:C" -> ElectricityMeter.ChannelId.CURRENT_L3;
		case "frequency:A" -> ElectricityMeter.ChannelId.FREQUENCY;
		default -> null;
		};
	}

	private void setLong(io.openems.edge.common.channel.ChannelId channel, Object value, BigDecimal multiplier) {
		if (value != null) {
			this.channel(channel).setNextValue(new BigDecimal(value.toString()).multiply(multiplier).longValue());
		}
	}

	private void applyFailure(Throwable throwable) {
		var cause = unwrap(throwable);
		var message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
		this.channel(ElsterA1700Meter.ChannelId.COMMUNICATION_FAILED).setNextValue(true);
		this.channel(ElsterA1700Meter.ChannelId.AUTHENTICATION_FAILED)
				.setNextValue(message.toLowerCase().contains("authentication"));
		this.channel(ElsterA1700Meter.ChannelId.WRONG_METER)
				.setNextValue(message.contains("connected meter"));
		this.channel(ElsterA1700Meter.ChannelId.LAST_ERROR).setNextValue(message);
		this.log.warn("A1700 operation failed: {}", message);
	}

	private static Throwable unwrap(Throwable error) {
		var result = error;
		while ((result instanceof ExecutionException || result instanceof java.util.concurrent.CompletionException)
				&& result.getCause() != null) {
			result = result.getCause();
		}
		return result;
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(READ_CURRENT, call -> response(call.getRequest().getId(), this.await(this.read("current", null))));
		builder.handleRequest(READ_INSTANTANEOUS,
				call -> response(call.getRequest().getId(), this.await(this.read("instantaneous", null))));
		builder.handleRequest(READ_HISTORICAL,
				call -> response(call.getRequest().getId(), this.await(this.read("historical", null))));
		builder.handleRequest(READ_PROFILE, call -> {
			var params = call.getRequest().getParams();
			var date = optionalDate(params, "date");
			var fromDate = optionalDate(params, "fromDate");
			var toDate = optionalDate(params, "toDate");
			if (date != null && (fromDate != null || toDate != null)) {
				throw new IllegalArgumentException("Use either date or fromDate/toDate");
			}
			if (date != null) {
				fromDate = date;
				toDate = date;
			} else if (fromDate != null || toDate != null) {
				fromDate = fromDate == null ? toDate : fromDate;
				toDate = toDate == null ? fromDate : toDate;
			}
			if (fromDate != null && fromDate.isAfter(toDate)) {
				throw new IllegalArgumentException("fromDate must not be after toDate");
			}
			var selector = params.has("selector") ? params.get("selector").getAsString()
					: fromDate == null ? this.config.profileSelector()
							: profileSelectorForDates(fromDate, toDate, LocalDate.now());
			var result = this.await(this.read("load-profile", selector));
			if (fromDate != null) {
				A1700Decoder.filterProfileByDate(result, fromDate, toDate);
			}
			return response(call.getRequest().getId(), result);
		});
	}

	private static LocalDate optionalDate(com.google.gson.JsonObject params, String name) {
		if (!params.has(name) || params.get(name).isJsonNull()) {
			return null;
		}
		try {
			return LocalDate.parse(params.get(name).getAsString());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(name + " must use YYYY-MM-DD");
		}
	}

	private Map<String, Object> await(CompletableFuture<Map<String, Object>> future) throws Exception {
		return future.get(15, TimeUnit.MINUTES);
	}

	private static GenericJsonrpcResponseSuccess response(UUID id, Map<String, Object> result) {
		JsonElement json = JsonParser.parseString(A1700Data.prettyJson(result));
		return new GenericJsonrpcResponseSuccess(id, json.getAsJsonObject());
	}

	@Override
	public MeterType getMeterType() {
		return this.meterType;
	}

	@Override
	public String debugLog() {
		return "P:" + this.getActivePower().asString() + " W";
	}

	@Override
	public void _setIec6205621CommunicationFailed(boolean value) {
		this.channel(ElsterA1700Meter.ChannelId.COMMUNICATION_FAILED).setNextValue(value);
	}

	@Override
	@Deactivate
	protected synchronized void deactivate() {
		this.componentActive = false;
		this.unregisterProtocol();
		Arrays.fill(this.activeKey, (byte) 0);
		super.deactivate();
	}
}
