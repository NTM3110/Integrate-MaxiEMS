package io.openems.edge.bridge.edmi;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.atdigital.imr.EdmiRegisters;
import com.atdigital.imr.ImrCoreLib;
import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.InfluxDBClientOptions;
import com.influxdb.client.domain.InfluxQLQuery;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.influxdb.query.InfluxQLQueryResult;
import io.openems.common.oem.OpenemsEdgeOem;
import io.openems.shared.influxdb.InfluxConnector;
import io.openems.shared.influxdb.QueryLanguageConfig;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.influxdb.client.write.Point;

import io.openems.edge.bridge.edmi.api.EdmiTask;
import io.openems.edge.bridge.edmi.api.EdmiWorker;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import com.atdigital.imr.MeterClient;
import com.atdigital.imr.EdmiDateTime;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.influxdb.TimedataInfluxDb;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Bridge.EDMI", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class EdmiBridgeImpl extends AbstractOpenemsComponent implements EdmiBridge, OpenemsComponent {
	private static final String BILLING_MEASUREMENT = "edmi_billing_values";
	private static final String PROFILE_MEASUREMENT = "edmi_profile";
	private static final Pattern SAFE_FIELD_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final DateTimeFormatter RESPONSE_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private final Logger log = LoggerFactory.getLogger(EdmiBridgeImpl.class);

	private EdmiWorker worker;

	private InfluxConnector influxConnector;

	private String portName = "";
	private int baudRate;
	private int dataBit;
	private int stopBit;
	private int parityBit;
	private URI influxUrl;
	private String influxOrg;
	private String influxApiKey;
	private String influxBucket;
	private QueryLanguageConfig queryLanguage;
	private ProfileIngestionSettings profileSettings = ProfileIngestionSettings.from(new DefaultConfig());
	private final Map<String, EdmiEnergyMeter> energyMeters = new ConcurrentHashMap<>();

	private void applyConfig(Config config) {
		this.influxConnector = new InfluxConnector(config.id(), config.queryLanguage(), URI.create(config.url()),
				config.org(), config.apiKey(), config.bucket(), this.oem.getInfluxdbTag(), config.isReadOnly(), 5,
				config.maxQueueSize(), //
				(e) -> {
					// ignore
				});
		this.baudRate = config.baudRate();
		this.portName = config.portName();
		this.dataBit = config.databits();
		this.stopBit = config.stopbits().ordinal();
		this.parityBit = config.parity().ordinal();
		this.influxUrl = URI.create(config.url());
		this.influxOrg = config.org();
		this.influxApiKey = config.apiKey();
		this.influxBucket = config.bucket();
		this.queryLanguage = config.queryLanguage();
		this.profileSettings = ProfileIngestionSettings.from(config);

		this.worker = new EdmiWorker(this, point -> {
			influxConnector.write(point);
		}, this.profileSettings.retryMillis());
		this.worker.activate(config.id());

	}

	@Reference
	private OpenemsEdgeOem oem;

	public EdmiBridgeImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				EdmiBridge.ChannelId.values() //
		);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		applyConfig(config);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.worker != null) {
			this.worker.deactivate();
		}
		super.deactivate();
	}

	@Override
	public void addTask(EdmiTask task) {
		this.worker.addTask(task);
	}

	@Override
	public void removeTask(EdmiTask task) {
		this.worker.removeTask(task);
	}

	@Override
	public ProfileIngestionSettings getProfileIngestionSettings() {
		return this.profileSettings;
	}

	@Override
	public void registerEnergyMeter(String meterId, String role, String sourceType) {
		if (meterId == null || meterId.isBlank()) {
			return;
		}
		this.energyMeters.put(meterId, new EdmiEnergyMeter(meterId, role, sourceType));
	}

	@Override
	public void unregisterEnergyMeter(String meterId) {
		if (meterId != null) {
			this.energyMeters.remove(meterId);
		}
	}

	@Override
	public int getEnergyMeterCount(String role, String sourceType) {
		if (role == null) {
			return 0;
		}
		int count = 0;
		for (EdmiEnergyMeter meter : this.energyMeters.values()) {
			if (role.equals(meter.role())) {
				if (sourceType == null || sourceType.equals(meter.sourceType())) {
					count++;
				}
			}
		}
		return count;
	}

	@Override
	public Map<String, EdmiEnergyMeter> getEnergyMeters() {
		return new ConcurrentHashMap<>(this.energyMeters);
	}


	@Override
	public void writeToInflux(Point point) {
		this.worker.writeToInflux(point);
	}

	@Override
	public void writeToInfluxSync(Point point) {
		// Write directly to InfluxDB using blocking API, bypassing the async worker queue
		this.influxConnector.writeSync(point);
	}

	@Override
	public Object readProfile(EdmiDateTime.ByValue from, EdmiDateTime.ByValue to, int serialNumber, String username, String password, short survey) throws Exception{
		this.log.debug("Sending EDMI Profile Reading Request");
		return this.readProfileFromHardware(from, to, serialNumber, username, password, survey);
	}

	@Override
	public Object readProfileImmediately(EdmiDateTime.ByValue from, EdmiDateTime.ByValue to, int serialNumber,
			String username, String password, short survey) throws Exception {
		return this.worker.executeImmediately(() -> this.readProfileFromHardware(from, to, serialNumber, username, password, survey));
	}

	private Object readProfileFromHardware(EdmiDateTime.ByValue from, EdmiDateTime.ByValue to, int serialNumber,
			String username, String password, short survey) throws Exception {
		MeterClient.listPorts();
		ImrCoreLib.INSTANCE.Init();
		MeterClient client = new MeterClient(this.portName,this.baudRate,this.dataBit, this.stopBit, this.parityBit);
		client.connect();
		try {
			if (!client.login(username, password, serialNumber)) {
				System.out.println("Login failed — check COM port, baud rate, serial, credentials.");
				return null;
			}
			return client.readProfile(survey, from, to);
		} finally {
			client.logout();
			client.disconnect();
		}
	}

	@Override
	public List<Object> readBillingValues(String username, String password, int serialNumber) throws  Exception{
		this.log.warn("Sending EDMI Billing values Reading Request");
		this.log.info("Username = {}, password = {}, serialNumber = {}", username, password, serialNumber);
		MeterClient.listPorts();
		ImrCoreLib.INSTANCE.Init();
		MeterClient client = new MeterClient(this.portName,this.baudRate,this.dataBit, this.stopBit, this.parityBit);
		client.connect();
		try {
			if (!client.login(username, password, serialNumber)) {
				System.out.println("Login failed — check COM port, baud rate, serial, credentials.");
				return null;
			}
			return client.readBillingValues();
		} finally {
			client.logout();
			client.disconnect();
		}
	}

	@Override
	public JsonArray queryBillingValuesFromInflux(String meterId, Instant start, Instant end, List<String> fields)
			throws Exception {
		this.validateQueryArguments(meterId, start, end, fields);
		try (var client = this.createInfluxClient()) {
			return switch (this.queryLanguage) {
			case FLUX -> this.queryValuesByFlux(client, BILLING_MEASUREMENT, meterId, start, end, fields);
			case INFLUX_QL -> this.queryValuesByInfluxQl(client, BILLING_MEASUREMENT, meterId, start, end, fields);
			};
		}
	}

	@Override
	public JsonArray queryProfileValuesFromInflux(String meterId, Instant start, Instant end, List<String> fields)
			throws Exception {
		this.validateQueryArguments(meterId, start, end, fields);
		try (var client = this.createInfluxClient()) {
			return switch (this.queryLanguage) {
			case FLUX -> this.queryValuesByFlux(client, PROFILE_MEASUREMENT, meterId, start, end, fields);
			case INFLUX_QL -> this.queryValuesByInfluxQl(client, PROFILE_MEASUREMENT, meterId, start, end, fields);
			};
		}
	}

	private void validateQueryArguments(String meterId, Instant start, Instant end, List<String> fields) {
		if (meterId == null || meterId.isBlank()) {
			throw new IllegalArgumentException("meterId is required.");
		}
		this.validateTimeAndFields(start, end, fields);
	}

	private void validateTimeAndFields(Instant start, Instant end, List<String> fields) {
		if (start == null || end == null) {
			throw new IllegalArgumentException("start and end are required.");
		}
		if (start.isAfter(end)) {
			throw new IllegalArgumentException("start must be before or equal to end.");
		}
		if (fields == null || fields.isEmpty()) {
			throw new IllegalArgumentException("At least one field is required.");
		}
		for (var field : fields) {
			if (field == null || !SAFE_FIELD_PATTERN.matcher(field).matches()) {
				throw new IllegalArgumentException("Unsupported field name: [" + field + "]");
			}
		}
	}

	private InfluxDBClient createInfluxClient() {
		var options = InfluxDBClientOptions.builder() //
				.url(this.influxUrl.toString()) //
				.org(this.influxOrg) //
				.bucket(this.influxBucket);
		if (this.influxApiKey != null && !this.influxApiKey.isBlank()) {
			options.authenticateToken(this.influxApiKey.toCharArray());
		}
		return InfluxDBClientFactory.create(options.build()).enableGzip();
	}

	private JsonArray queryValuesByFlux(InfluxDBClient client, String measurement, String meterId, Instant start,
			Instant end, List<String> fields) {
		var fieldFilter = fields.stream() //
				.map(field -> "r._field == \"" + this.escapeFluxString(field) + "\"") //
				.reduce((left, right) -> left + " or " + right) //
				.orElseThrow();
		var query = """
				from(bucket: "%s")
				  |> range(start: time(v: "%s"), stop: time(v: "%s"))
				  |> filter(fn: (r) => r._measurement == "%s")
				  %s
				  |> filter(fn: (r) => %s)
				  |> keep(columns: ["_time", "_field", "_value", "meter_id"])
				  |> sort(columns: ["_time"])
				""".formatted(//
				this.escapeFluxString(this.influxBucket), //
				start.toString(), //
				end.plusMillis(1).toString(), //
				this.escapeFluxString(measurement), //
				meterId == null ? "" : "|> filter(fn: (r) => r.meter_id == \"" + this.escapeFluxString(meterId) + "\")", //
				fieldFilter);

		var recordsByTime = new TreeMap<Instant, JsonObject>();
		List<FluxTable> tables = client.getQueryApi().query(query);
		for (var table : tables) {
			for (FluxRecord record : table.getRecords()) {
				var timestamp = record.getTime();
				if (timestamp == null) {
					continue;
				}
				var json = recordsByTime.computeIfAbsent(timestamp, ignored -> {
					var obj = new JsonObject();
					obj.addProperty("time", this.formatResponseTime(timestamp));
					if (meterId != null) {
						obj.addProperty("meterId", meterId);
					}
					return obj;
				});
				json.add(record.getField(), this.toJsonValue(record.getValue()));
			}
		}
		return this.toJsonArray(recordsByTime);
	}

	private JsonArray queryValuesByInfluxQl(InfluxDBClient client, String measurement, String meterId, Instant start,
			Instant end, List<String> fields) {
		var selectFields = String.join(", ", fields.stream().map(field -> "\"" + field + "\"").toList());
		var query = "SELECT " + selectFields //
				+ " FROM \"" + measurement + "\"" //9
				+ " WHERE " //
				+ (meterId == null ? "" : "\"meter_id\" = '" + this.escapeInfluxQlString(meterId) + "' AND ") //
				+ "time >= " + start.toEpochMilli() + "ms" //
				+ " AND time <= " + end.toEpochMilli() + "ms" //
				+ " ORDER BY time ASC";
		var database = this.influxBucket.split("/")[0];
		var result = client.getInfluxQLQueryApi().query(new InfluxQLQuery(query, database) //
				.setPrecision(InfluxQLQuery.InfluxQLPrecision.MILLISECONDS));

		var recordsByTime = new TreeMap<Instant, JsonObject>();
		if (result == null || result.getResults() == null) {
			return new JsonArray();
		}
		for (var queryResult : result.getResults()) {
			this.appendInfluxQlSeries(recordsByTime, queryResult, meterId, fields);
		}
		return this.toJsonArray(recordsByTime);
	}

	private void appendInfluxQlSeries(Map<Instant, JsonObject> recordsByTime, InfluxQLQueryResult.Result queryResult,
			String meterId, List<String> fields) {
		if (queryResult == null || queryResult.getSeries() == null) {
			return;
		}
		for (var series : queryResult.getSeries()) {
			if (series.getValues() == null) {
				continue;
			}
			for (var row : series.getValues()) {
				var timeValue = row.getValueByKey("time");
				if (timeValue == null) {
					continue;
				}
				var timestamp = Instant.ofEpochMilli(Long.parseLong(timeValue.toString()));
				var json = recordsByTime.computeIfAbsent(timestamp, ignored -> {
					var obj = new JsonObject();
					obj.addProperty("time", this.formatResponseTime(timestamp));
					if (meterId != null) {
						obj.addProperty("meterId", meterId);
					}
					return obj;
				});
				for (var field : fields) {
					json.add(field, this.toJsonValue(row.getValueByKey(field)));
				}
			}
		}
	}

	private JsonArray toJsonArray(Map<Instant, JsonObject> recordsByTime) {
		var result = new JsonArray();
		for (var row : recordsByTime.values()) {
			result.add(row);
		}
		return result;
	}

	private com.google.gson.JsonElement toJsonValue(Object value) {
		if (value == null) {
			return com.google.gson.JsonNull.INSTANCE;
		}
		if (value instanceof Number number) {
			return new com.google.gson.JsonPrimitive(number);
		}
		if (value instanceof Boolean bool) {
			return new com.google.gson.JsonPrimitive(bool);
		}
		return new com.google.gson.JsonPrimitive(value.toString());
	}

	private String escapeFluxString(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String escapeInfluxQlString(String value) {
		return value.replace("\\", "\\\\").replace("'", "\\'");
	}

	private String formatResponseTime(Instant timestamp) {
		return RESPONSE_TIME_FORMATTER.format(timestamp.atZone(ZoneId.systemDefault()));
	}

	private static final class DefaultConfig implements Config {
		@Override
		public Class<? extends java.lang.annotation.Annotation> annotationType() {
			return Config.class;
		}

		@Override
		public String id() { return "edmi0"; }
		@Override
		public String alias() { return ""; }
		@Override
		public boolean enabled() { return true; }
		@Override
		public String portName() { return "/dev/ttyUSB0"; }
		@Override
		public int baudRate() { return 9600; }
		@Override
		public int databits() { return 8; }
		@Override
		public Stopbit stopbits() { return Stopbit.ONE; }
		@Override
		public Parity parity() { return Parity.NONE; }
		@Override
		public QueryLanguageConfig queryLanguage() { return QueryLanguageConfig.INFLUX_QL; }
		@Override
		public String url() { return "http://localhost:8086"; }
		@Override
		public String org() { return "-"; }
		@Override
		public String apiKey() { return ""; }
		@Override
		public String bucket() { return ""; }
		@Override
		public String measurement() { return "data"; }
		@Override
		public int noOfCycles() { return 1; }
		@Override
		public int maxQueueSize() { return 5000; }
		@Override
		public boolean isReadOnly() { return false; }
		@Override
		public String profileSurvey() { return "0x0325"; }
		@Override
		public int profileIntervalMinutes() { return 30; }
		@Override
		public int profileFirstReadDelayMinutes() { return 5; }
		@Override
		public int profileRetryMinutes() { return 5; }
		@Override
		public int profileFinalizeAfterMinutes() { return 30; }
		@Override
		public int profileMaxRecords() { return 5; }
		@Override
		public String profileTimestampAliases() { return "DateTime|time_stamp|timestamp"; }
		@Override
		public String profileRecordStatusAliases() { return "Record Status|record_status|EFA Status"; }
		@Override
		public String profileImportWhAliases() { return "Total Energy Tot IMP Wh @|To IMP Wh|Tot IMP Wh"; }
		@Override
		public String profileExportWhAliases() { return "Total Energy Tot EXP Wh @|To EXP Wh|Tot EXP Wh"; }
		@Override
		public String profileImportVahAliases() { return "Total Energy Tot IMP va @|To IMP va|Tot IMP va"; }
		@Override
		public String profileExportVahAliases() { return "Total Energy Tot EXP va @|To EXP va|Tot EXP va"; }
		@Override
		public String webconsole_configurationFactory_nameHint() { return "Bridge EDMI [{id}]"; }
	}
}
