package io.openems.edge.meter.landis.dlms;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.types.MeterType;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.bridge.dlms.api.AbstractOpenemsDlmsComponent;
import io.openems.edge.bridge.dlms.api.BridgeDlms;
import io.openems.edge.bridge.dlms.api.DlmsBatchTarget;
import io.openems.edge.bridge.dlms.api.DlmsComponent;
import io.openems.edge.bridge.dlms.api.DlmsProtocol;
import io.openems.edge.bridge.dlms.api.DlmsTargetConfig;
import io.openems.edge.bridge.dlms.api.task.DlmsProfileCsvStore;
import io.openems.edge.bridge.dlms.api.task.ReadProfileTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Landis.Dlms", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class LandisDlmsMeterImpl extends AbstractOpenemsDlmsComponent
		implements DlmsComponent, DlmsTargetConfig, LandisDlmsMeter, OpenemsComponent, ComponentJsonApi {

	private MeterType meterType = MeterType.GRID;
	private static final ZoneId DEVICE_ZONE = ZoneId.systemDefault();
	private String outstation;
	private boolean autoReadProfile;
	private int serverAddress;
	private String profileObis;
	private LocalTime autoReadProfileTime;
	private String profileCsvDataDir;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setDlms(BridgeDlms dlms) {
		super.setDlms(dlms);
	}

	@Reference
	protected ConfigurationAdmin cm;

	public LandisDlmsMeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				DlmsComponent.ChannelId.values(), //
				LandisDlmsMeter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.applyConfig(config);
		if (super.activate(context, config.id(), config.alias(), config.enabled(), this.cm, "dlms", config.dlms_id())) {
			return;
		}
	}

	@Modified
	private void modified(ComponentContext context, Config config) throws OpenemsException {
		this.applyConfig(config);
		this.resetDlmsProtocol();
		if (super.modified(context, config.id(), config.alias(), config.enabled(), this.cm, "dlms", config.dlms_id())) {
			return;
		}
	}

	private void applyConfig(Config config) {
		this.outstation = config.outstation();
		this.autoReadProfile = config.autoReadProfile();
		this.serverAddress = config.serverAddress();
		this.profileObis = config.profileObis();
		this.autoReadProfileTime = LocalTime.parse(config.autoReadProfileTime());
		this.profileCsvDataDir = config.profileCsvDataDir();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public String debugLog() {
		return "Export: " + this.channel(LandisDlmsMeter.ChannelId.ACTIVE_POWER_EXPORT).value().asString();
	}

	@Override
	protected DlmsProtocol defineDlmsProtocol() {
		var protocol = new DlmsProtocol(this);
		if (this.autoReadProfile) {
			protocol.addTask(new ReadProfileTask(Priority.HIGH, this.outstation, this.profileObis, this.autoReadProfileTime,
					() -> this.profileCsvDataDir));
		}
		return protocol;
	}

	@Override
	public int serverAddress() {
		return this.serverAddress;
	}

	/**
	 * Method name for the JSON-RPC endpoint.
	 */
	public static final String GET_DLMS_PROFILE_METHOD = "getDlmsProfile";
	public static final String GET_DLMS_BILLING_VALUE = "getDlmsBillingValues";
	public static final String GET_DLMS_PROFILE_BATCH_METHOD = "getDlmsProfileBatch";
	public static final String GET_DLMS_BILLING_VALUE_BATCH = "getDlmsBillingValuesBatch";
	public static final String EXPORT_DLMS_PROFILE_CSV_METHOD = "exportDlmsProfileCsv";
	public static final String GET_DLMS_PROFILE_MISSING_DATA_METHOD = "getDlmsProfileMissingData";

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET_DLMS_PROFILE_METHOD, call -> {
			var params = call.getRequest().getParams();
			String obis = params.has("obis") ? JsonUtils.getAsString(params, "obis") : this.profileObis;
			String startStr = JsonUtils.getAsString(params, "startDate"); // "yyyy-MM-dd HH:mm:ss"
			String endStr   = JsonUtils.getAsString(params, "endDate");
			String csvDataDir = params.has("csvDataDir") ? JsonUtils.getAsString(params, "csvDataDir") : this.profileCsvDataDir;
			String outstation = params.has("outstation") ? JsonUtils.getAsString(params, "outstation") : this.outstation;

			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date start = sdf.parse(startStr);
			Date end   = sdf.parse(endStr);
			LocalDateTime startDateTime = LocalDateTime.ofInstant(start.toInstant(), DEVICE_ZONE);
			LocalDateTime endDateTime = LocalDateTime.ofInstant(end.toInstant(), DEVICE_ZONE);
			LocalDate targetDate = startDateTime.toLocalDate();
			if (!targetDate.equals(endDateTime.toLocalDate())) {
				throw new IllegalArgumentException("startDate and endDate must be on the same day for CSV profile cache.");
			}
			if (targetDate.isAfter(LocalDate.now(DEVICE_ZONE))) {
				throw new IllegalArgumentException("target date must not be in the future.");
			}

			var csvStore = new DlmsProfileCsvStore(csvDataDir);
			int startIndex = DlmsProfileCsvStore.intervalIndex(startDateTime);
			int endIndex = DlmsProfileCsvStore.intervalIndex(endDateTime);
			int cacheEndIndex = this.cacheEndIndex(targetDate, endIndex);
			boolean fromCsv = csvStore.isComplete(targetDate, outstation, startIndex, cacheEndIndex);
			JsonArray result = new JsonArray();

			if (fromCsv) {
				for (var row : csvStore.filter(csvStore.read(targetDate, outstation), startIndex, cacheEndIndex)) {
					JsonArray jsonRow = new JsonArray();
					for (var cell : row) {
						jsonRow.add(cell);
					}
					result.add(jsonRow);
				}
			} else {
				//This stands for getting the setup configured BridgeDlmsSerialImpl component
				AbstractDlmsBridge bridge = (AbstractDlmsBridge) this.getBridgeDlms();
				if (bridge == null) {
					throw new IllegalStateException("DLMS bridge is not connected");
				}
				//The BridgeDlmsSerialImpl is the children of AbstractDlmsBridge. The first ever declaration of readProfile is from @AbstractDlms.java. However the readl implementation is from BridgeDlmsSerialImpl. This will be decided by the dlms_id in the configuration.
				var dayStart = Date.from(
						LocalDateTime.of(targetDate, LocalTime.MIN).minusMinutes(30).atZone(DEVICE_ZONE).toInstant());
				var dayEndTime = targetDate.equals(LocalDate.now(DEVICE_ZONE)) ? LocalDateTime.now(DEVICE_ZONE)
						: LocalDateTime.of(targetDate.plusDays(1), LocalTime.MIN);
				var dayEnd = Date.from(dayEndTime.atZone(DEVICE_ZONE).toInstant());
				Object[] rows = bridge.readProfileViaWorker(this, obis, dayStart, dayEnd);
				var csvRows = csvStore.mergeAndSave(targetDate, outstation, rows);
				for (var row : csvStore.filter(csvRows, startIndex, cacheEndIndex)) {
					JsonArray jsonRow = new JsonArray();
					for (var cell : row) {
						jsonRow.add(cell);
					}
					result.add(jsonRow);
				}
			}
			JsonObject resultObj = new JsonObject();
			resultObj.add("rows", result);
			resultObj.addProperty("source", fromCsv ? "csv" : "meter_merged");
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(GET_DLMS_BILLING_VALUE, call -> {
			AbstractDlmsBridge bridge = (AbstractDlmsBridge) this.getBridgeDlms();
			if (bridge == null) {
				throw new IllegalStateException("DLMS bridge is not connected");
			}
			Object[] objects = bridge.readBillingValuesViaWorker(this);
			JsonArray result = new JsonArray();
			for (Object object : objects) {
				@SuppressWarnings("unchecked")
				Map<String, Object> data = (Map<String, Object>) object;
				JsonObject json = new JsonObject();
				
				// Handle potential nulls and extract correctly from 'data' (not 'object')
				json.addProperty("obis", data.get("obis") != null ? data.get("obis").toString() : null);
				json.addProperty("description", data.get("description") != null ? data.get("description").toString() : null);
				
				Object val = data.get("value");
				if (val instanceof Number) {
					json.addProperty("value", (Number) val);
				} else {
					json.addProperty("value", val != null ? val.toString() : null);
				}
				
				json.addProperty("unit", data.get("unit") != null ? data.get("unit").toString() : null);
				result.add(json);
			}
			JsonObject resultObj = new JsonObject();
			resultObj.add("objects", result);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(GET_DLMS_BILLING_VALUE_BATCH, call -> {
			AbstractDlmsBridge bridge = (AbstractDlmsBridge) this.getBridgeDlms();
			if (bridge == null) {
				throw new IllegalStateException("DLMS bridge is not connected");
			}
			var targets = this.parseBatchTargets(call.getRequest().getParams());
			var results = bridge.readBillingValuesBatchViaWorker(targets);
			JsonObject metersJson = new JsonObject();
			for (var target : targets) {
				var result = new JsonArray();
				var objects = results.get(target.outstation());
				if (objects != null) {
					for (Object object : objects) {
						@SuppressWarnings("unchecked")
						Map<String, Object> data = (Map<String, Object>) object;
						result.add(this.billingObjectToJson(data));
					}
				}
				var meterJson = new JsonObject();
				meterJson.addProperty("outstation", target.outstation());
				meterJson.add("objects", result);
				metersJson.add(target.outstation(), meterJson);
			}
			JsonObject resultObj = new JsonObject();
			resultObj.add("meters", metersJson);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(GET_DLMS_PROFILE_BATCH_METHOD, call -> {
			var params = call.getRequest().getParams();
			AbstractDlmsBridge bridge = (AbstractDlmsBridge) this.getBridgeDlms();
			if (bridge == null) {
				throw new IllegalStateException("DLMS bridge is not connected");
			}
			String csvDataDir = params.has("csvDataDir") ? JsonUtils.getAsString(params, "csvDataDir") : this.profileCsvDataDir;
			String startStr = JsonUtils.getAsString(params, "startDate");
			String endStr = JsonUtils.getAsString(params, "endDate");
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			Date start = sdf.parse(startStr);
			Date end = sdf.parse(endStr);
			LocalDateTime startDateTime = LocalDateTime.ofInstant(start.toInstant(), DEVICE_ZONE);
			LocalDateTime endDateTime = LocalDateTime.ofInstant(end.toInstant(), DEVICE_ZONE);
			LocalDate targetDate = startDateTime.toLocalDate();
			if (!targetDate.equals(endDateTime.toLocalDate())) {
				throw new IllegalArgumentException("startDate and endDate must be on the same day for CSV profile cache.");
			}
			if (targetDate.isAfter(LocalDate.now(DEVICE_ZONE))) {
				throw new IllegalArgumentException("target date must not be in the future.");
			}

			var csvStore = new DlmsProfileCsvStore(csvDataDir);
			int startIndex = DlmsProfileCsvStore.intervalIndex(startDateTime);
			int endIndex = DlmsProfileCsvStore.intervalIndex(endDateTime);
			int cacheEndIndex = this.cacheEndIndex(targetDate, endIndex);
			var targets = this.parseBatchTargets(params);
			var meterReadTargets = new ArrayList<DlmsBatchTarget>();
			JsonObject metersJson = new JsonObject();

			for (var target : targets) {
				boolean fromCsv = csvStore.isComplete(targetDate, target.outstation(), startIndex, cacheEndIndex);
				if (fromCsv) {
					metersJson.add(target.outstation(), this.profileRowsToResult(target.outstation(), "csv",
							csvStore.filter(csvStore.read(targetDate, target.outstation()), startIndex, cacheEndIndex)));
				} else {
					var dayStart = Date.from(
							LocalDateTime.of(targetDate, LocalTime.MIN).minusMinutes(30).atZone(DEVICE_ZONE).toInstant());
					var dayEndTime = targetDate.equals(LocalDate.now(DEVICE_ZONE)) ? LocalDateTime.now(DEVICE_ZONE)
							: LocalDateTime.of(targetDate.plusDays(1), LocalTime.MIN);
					var dayEnd = Date.from(dayEndTime.atZone(DEVICE_ZONE).toInstant());
					meterReadTargets.add(new DlmsBatchTarget(target.componentId(), target.outstation(),
							target.serverAddress(), target.profileObis(), dayStart, dayEnd));
				}
			}

			if (!meterReadTargets.isEmpty()) {
				var rowsByMeter = bridge.readProfileBatchViaWorker(meterReadTargets);
				for (var target : meterReadTargets) {
					var csvRows = csvStore.mergeAndSave(targetDate, target.outstation(), rowsByMeter.get(target.outstation()));
					metersJson.add(target.outstation(), this.profileRowsToResult(target.outstation(), "meter_merged",
							csvStore.filter(csvRows, startIndex, cacheEndIndex)));
				}
			}

			JsonObject resultObj = new JsonObject();
			resultObj.add("meters", metersJson);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(EXPORT_DLMS_PROFILE_CSV_METHOD, call -> {
			var params = call.getRequest().getParams();
			String csvDataDir = params.has("csvDataDir") ? JsonUtils.getAsString(params, "csvDataDir") : this.profileCsvDataDir;
			var startDate = parseLocalDateParam(JsonUtils.getAsString(params, "startDate"));
			var endDate = parseLocalDateParam(JsonUtils.getAsString(params, "endDate"));
			if (endDate.isBefore(startDate)) {
				throw new IllegalArgumentException("endDate must be on or after startDate.");
			}
			var startTime = params.has("startTime") ? LocalTime.parse(JsonUtils.getAsString(params, "startTime"))
					: LocalTime.MIN;
			var endTime = params.has("endTime") ? LocalTime.parse(JsonUtils.getAsString(params, "endTime"))
					: LocalTime.of(23, 30);
			var startIndex = DlmsProfileCsvStore.intervalIndex(LocalDateTime.of(startDate, startTime));
			var endIndex = DlmsProfileCsvStore.intervalIndex(LocalDateTime.of(startDate, endTime));
			var csvStore = new DlmsProfileCsvStore(csvDataDir);
			var targets = this.parseBatchTargets(params);
			var files = new ArrayList<CsvExportFile>();
			JsonArray missingReport = new JsonArray();

			for (var date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
				for (var target : targets) {
					var outstation = target.outstation();
					if (!csvStore.exists(date, outstation)) {
						addMissingReport(missingReport, outstation, date, true, List.of());
						continue;
					}
					var rows = csvStore.read(date, outstation);
					files.add(new CsvExportFile(csvStore.getFile(date, outstation).getFileName().toString(),
							DlmsProfileCsvStore.toCsv(rows).getBytes(StandardCharsets.UTF_8)));
					var missing = csvStore.findMissingIntervals(date, outstation, startIndex, endIndex);
					if (!missing.isEmpty()) {
						addMissingReport(missingReport, outstation, date, false, missing);
					}
				}
			}

			var missingCsv = missingReport.size() > 0 ? missingReportCsv(missingReport).getBytes(StandardCharsets.UTF_8)
					: null;
			byte[] payload;
			String fileName;
			String mimeType;
			if (files.size() == 1 && missingCsv == null) {
				var file = files.get(0);
				payload = file.bytes();
				fileName = file.name();
				mimeType = "text/csv";
			} else if (files.isEmpty()) {
				payload = missingCsv != null ? missingCsv : new byte[0];
				fileName = "missing_profile_data.csv";
				mimeType = "text/csv";
			} else {
				payload = zipExport(files, missingCsv);
				fileName = "profile_csv_" + startDate + "_to_" + endDate + ".zip";
				mimeType = "application/zip";
			}

			JsonObject resultObj = new JsonObject();
			resultObj.addProperty("fileName", fileName);
			resultObj.addProperty("mimeType", mimeType);
			resultObj.addProperty("contentBase64", Base64.getEncoder().encodeToString(payload));
			resultObj.add("missingReport", missingReport);
			resultObj.addProperty("fileCount", files.size());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});

		builder.handleRequest(GET_DLMS_PROFILE_MISSING_DATA_METHOD, call -> {
			var params = call.getRequest().getParams();
			String csvDataDir = params.has("csvDataDir") ? JsonUtils.getAsString(params, "csvDataDir") : this.profileCsvDataDir;
			var startDate = parseLocalDateParam(JsonUtils.getAsString(params, "startDate"));
			var endDate = parseLocalDateParam(JsonUtils.getAsString(params, "endDate"));
			if (endDate.isBefore(startDate)) {
				throw new IllegalArgumentException("endDate must be on or after startDate.");
			}
			var startTime = params.has("startTime") ? LocalTime.parse(JsonUtils.getAsString(params, "startTime"))
					: LocalTime.MIN;
			var endTime = params.has("endTime") ? LocalTime.parse(JsonUtils.getAsString(params, "endTime"))
					: LocalTime.of(23, 30);
			var startIndex = DlmsProfileCsvStore.intervalIndex(LocalDateTime.of(startDate, startTime));
			var endIndex = DlmsProfileCsvStore.intervalIndex(LocalDateTime.of(startDate, endTime));
			var csvStore = new DlmsProfileCsvStore(csvDataDir);
			var targets = this.parseBatchTargets(params);
			JsonArray report = new JsonArray();

			for (var date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
				for (var target : targets) {
					var outstation = target.outstation();
					if (!csvStore.exists(date, outstation)) {
						addMissingReport(report, outstation, date, true, List.of());
						continue;
					}
					var missing = csvStore.findMissingIntervals(date, outstation, startIndex, endIndex);
					addMissingReport(report, outstation, date, false, missing);
				}
			}

			JsonObject resultObj = new JsonObject();
			resultObj.add("report", report);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), resultObj);
		});
	}

	private static LocalDate parseLocalDateParam(String raw) {
		var value = raw.trim();
		if (value.length() >= 10) {
			value = value.substring(0, 10);
		}
		return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
	}

	private static void addMissingReport(JsonArray report, String outstation, LocalDate date, boolean noFile,
			List<String> missingIntervals) {
		var row = new JsonObject();
		row.addProperty("meter", outstation);
		row.addProperty("date", date.toString());
		row.addProperty("no_file", noFile);
		var intervals = new JsonArray();
		for (var interval : missingIntervals) {
			intervals.add(interval);
		}
		row.add("missing_intervals", intervals);
		report.add(row);
	}

	private static String missingReportCsv(JsonArray report) {
		var csv = new StringBuilder("meter,date,status,missing_intervals\r\n");
		for (var element : report) {
			var row = element.getAsJsonObject();
			var noFile = row.get("no_file").getAsBoolean();
			var missing = new ArrayList<String>();
			for (var interval : row.getAsJsonArray("missing_intervals")) {
				missing.add(interval.getAsString());
			}
			csv.append(csvCell(row.get("meter").getAsString())).append(',')
					.append(csvCell(row.get("date").getAsString())).append(',')
					.append(csvCell(noFile ? "NO_FILE" : "MISSING_INTERVALS")).append(',')
					.append(csvCell(String.join("; ", missing))).append("\r\n");
		}
		return csv.toString();
	}

	private static byte[] zipExport(List<CsvExportFile> files, byte[] missingCsv) throws Exception {
		var out = new ByteArrayOutputStream();
		try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (var file : files) {
				addZipEntry(zip, file.name(), file.bytes());
			}
			if (missingCsv != null) {
				addZipEntry(zip, "missing_profile_data.csv", missingCsv);
			}
		}
		return out.toByteArray();
	}

	private static void addZipEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}

	private static String csvCell(String value) {
		var safe = value == null ? "" : value;
		return "\"" + safe.replace("\"", "\"\"") + "\"";
	}

	private record CsvExportFile(String name, byte[] bytes) {
	}

	private List<DlmsBatchTarget> parseBatchTargets(JsonObject params) {
		var targets = new ArrayList<DlmsBatchTarget>();
		if (params.has("targets") && params.get("targets").isJsonArray()) {
			for (var element : params.getAsJsonArray("targets")) {
				var target = element.getAsJsonObject();
				var componentId = target.has("componentId") ? target.get("componentId").getAsString() : this.id();
				var outstation = target.has("outstation") ? target.get("outstation").getAsString() : this.outstation;
				var serverAddress = target.has("serverAddress") ? target.get("serverAddress").getAsInt() : this.serverAddress;
				var profileObis = target.has("profileObis") ? target.get("profileObis").getAsString() : this.profileObis;
				targets.add(new DlmsBatchTarget(componentId, outstation, serverAddress, profileObis, null, null));
			}
		}
		if (targets.isEmpty()) {
			targets.add(new DlmsBatchTarget(this.id(), this.outstation, this.serverAddress, this.profileObis, null, null));
		}
		return targets;
	}

	private JsonObject billingObjectToJson(Map<String, Object> data) {
		JsonObject json = new JsonObject();
		json.addProperty("obis", data.get("obis") != null ? data.get("obis").toString() : null);
		json.addProperty("description", data.get("description") != null ? data.get("description").toString() : null);
		Object val = data.get("value");
		if (val instanceof Number) {
			json.addProperty("value", (Number) val);
		} else {
			json.addProperty("value", val != null ? val.toString() : null);
		}
		json.addProperty("unit", data.get("unit") != null ? data.get("unit").toString() : null);
		return json;
	}

	private JsonObject profileRowsToResult(String outstation, String source, List<List<String>> rows) {
		JsonArray result = new JsonArray();
		for (var row : rows) {
			JsonArray jsonRow = new JsonArray();
			for (var cell : row) {
				jsonRow.add(cell);
			}
			result.add(jsonRow);
		}
		JsonObject meterJson = new JsonObject();
		meterJson.addProperty("outstation", outstation);
		meterJson.addProperty("source", source);
		meterJson.add("rows", result);
		return meterJson;
	}

	private int expectedCsvEndIndex(LocalDate targetDate) {
		var today = LocalDate.now(DEVICE_ZONE);
		if (targetDate.isBefore(today)) {
			return 47;
		}
		var now = LocalTime.now(DEVICE_ZONE);
		var currentInterval = now.getHour() * 2 + now.getMinute() / 30;
		return Math.min(currentInterval - 1, 47);
	}

	private int cacheEndIndex(LocalDate targetDate, int requestedEndIndex) {
		return Math.min(requestedEndIndex, this.expectedCsvEndIndex(targetDate));
	}
}
