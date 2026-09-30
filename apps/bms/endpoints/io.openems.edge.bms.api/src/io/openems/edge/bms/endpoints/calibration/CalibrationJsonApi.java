package io.openems.edge.bms.endpoints.calibration;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.contracts.CalibrationEventOperations;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeMethod;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionCode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CellSample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePoint;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePointPage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ReferenceCurve;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CompleteProcessCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfirmDischargeStoppedCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDemoTimeScaleCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeSafetyFloorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeMonitorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureFloatStablePolicyCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveFloatCurrentRangeCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveVocDeadbandCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeleteDraftCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventHistory;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventSummary;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ExecuteActionCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidence;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidenceCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Process;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Report;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Sample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.RedoStageCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Stage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Transition;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Authenticated JSON-RPC facade for immutable Calibration Event records. */
@Component(name = "io.openems.edge.bms.platform.calibration.CalibrationJsonApi", property = "entry=edge.websocket.root")
public class CalibrationJsonApi implements JsonApi {

	public static final String LIST = "bms.calibration.list";
	public static final String GET = "bms.calibration.get";
	public static final String CREATE = "bms.calibration.create";
	public static final String START = "bms.calibration.start";
	public static final String DELETE_DRAFT = "bms.calibration.deleteDraft";
	public static final String START_PROCESS = "bms.calibration.startProcess";
	public static final String COMPLETE_PROCESS = "bms.calibration.completeProcess";
	public static final String CONFIRM_DISCHARGE_STOPPED = "bms.calibration.confirmDischargeStopped";
	public static final String CONFIGURE_DEMO_TIME_SCALE = "bms.calibration.configureDemoTimeScale";
	public static final String CONFIGURE_DISCHARGE_SAFETY_FLOOR = "bms.calibration.configureDischargeSafetyFloor";
	public static final String CONFIGURE_DISCHARGE_MONITOR = "bms.calibration.configureDischargeMonitor";
	public static final String CONFIGURE_FLOAT_STABLE_POLICY = "bms.calibration.configureFloatStablePolicy";
	public static final String CONFIGURE_ACTIVE_FLOAT_CURRENT_RANGE = "bms.calibration.configureActiveFloatCurrentRange";
	public static final String CONFIGURE_ACTIVE_VOC_DEADBAND = "bms.calibration.configureActiveVocDeadband";
	public static final String APPEND_EVIDENCE = "bms.calibration.appendEvidence";
	public static final String EXECUTE_STAGE_ACTION = "bms.calibration.executeStageAction";
	public static final String REDO_STAGE = "bms.calibration.redoStage";
	public static final String SAMPLES = "bms.calibration.samples";
	public static final String CURVE_POINTS = "bms.calibration.curvePoints";
	public static final String HISTORY = "bms.calibration.history";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private CalibrationEventOperations service;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(LIST, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			var stringId = optionalUuid(params, "stringId");
			var stringIndex = optionalInt(params, "stringIndex");
			if (stringId != null && stringIndex != null) {
				throw new IllegalArgumentException("Specify either stringId or stringIndex, not both");
			}
			var events = stringIndex == null ? this.service.list(stringId, principal)
					: this.service.listByStringIndex(stringIndex, principal);
			var result = new JsonObject();
			var array = new JsonArray();
			events.forEach(value -> array.add(summary(value)));
			result.add("events", array);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest(GET, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					detail(this.service.get(requiredLong(params, "calibrationEventId"), principal)));
		});
		builder.handleRequest(CREATE, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(create(params, principal)));
		});
		builder.handleRequest(START, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.start(
					requiredLong(params, "calibrationEventId"), requiredLong(params, "expectedEventVersion"),
					principal)));
		});
		builder.handleRequest(DELETE_DRAFT, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			var deleted = this.service.deleteDraft(deleteDraftCommand(params), principal);
			var result = new JsonObject();
			result.addProperty("calibrationEventId", deleted.calibrationEventId());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest(START_PROCESS, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.startProcess(
					startProcessCommand(params), principal)));
		});
		builder.handleRequest(COMPLETE_PROCESS, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.completeProcess(
					completeProcessCommand(params), principal)));
		});
		builder.handleRequest(CONFIRM_DISCHARGE_STOPPED, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(
					this.service.confirmDischargeStopped(confirmDischargeStoppedCommand(params), principal)));
		});
		builder.handleRequest(CONFIGURE_DEMO_TIME_SCALE, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.configureDemoTimeScale(
					configureDemoTimeScaleCommand(params), principal)));
		});
		builder.handleRequest(CONFIGURE_DISCHARGE_SAFETY_FLOOR, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(
					this.service.configureDischargeSafetyFloor(configureDischargeSafetyFloorCommand(params), principal)));
		});
		builder.handleRequest(CONFIGURE_DISCHARGE_MONITOR, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(
					this.service.configureDischargeMonitor(configureDischargeMonitorCommand(params), principal)));
		});
		builder.handleRequest(CONFIGURE_FLOAT_STABLE_POLICY, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					detail(this.service.configureFloatStablePolicy(configureFloatStablePolicyCommand(params), principal)));
		});
		builder.handleRequest(CONFIGURE_ACTIVE_FLOAT_CURRENT_RANGE, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(
					this.service.configureActiveFloatCurrentRange(activeFloatCurrentRangeCommand(params), principal)));
		});
		builder.handleRequest(CONFIGURE_ACTIVE_VOC_DEADBAND, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(
					this.service.configureActiveVocDeadband(activeVocDeadbandCommand(params), principal)));
		});
		builder.handleRequest(APPEND_EVIDENCE, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.appendEvidence(
					evidenceCommand(params), principal)));
		});
		builder.handleRequest(EXECUTE_STAGE_ACTION, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.executeAction(
					actionCommand(params), principal)));
		});
		builder.handleRequest(REDO_STAGE, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), detail(this.service.redoStage(
					redoStageCommand(params), principal)));
		});
		builder.handleRequest(SAMPLES, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			var values = this.service.samples(requiredLong(params, "calibrationEventId"), principal);
			var result = new JsonObject();
			var array = new JsonArray();
			values.forEach(value -> array.add(sample(value)));
			result.add("samples", array);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest(CURVE_POINTS, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			var offset = optionalInt(params, "offset");
			var limit = optionalInt(params, "limit");
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), curvePointPage(this.service.curvePoints(
					requiredLong(params, "calibrationEventId"), optionalLong(params, "curveVersionId"),
					optionalInt(params, "cellIndex"), offset == null ? 0 : offset, limit == null ? 500 : limit,
					principal)));
		});
		builder.handleRequest(HISTORY, call -> {
			var params = call.getRequest().getParams();
			var principal = authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					history(this.service.history(requiredLong(params, "calibrationEventId"), principal)));
		});
	}

	private EventDetail create(JsonObject params, AuthenticatedPrincipal principal) throws SQLException {
		rejectFixedCalculationPolicy(params);
		var eventType = requiredEnum(params, "eventType", CalibrationEventType.class);
		var dischargeMethod = nullableEnum(params, "dischargeMethod", DischargeMethod.class);
		var endpointMode = nullableEnum(params, "dischargeEndpointMode", DischargeEndpointMode.class);
		var targetDod = optionalNumber(params, "targetDodPercent");
		var chargeCurrentLimitA = optionalNumber(params, "chargeCurrentLimitA");
		var safetyFloorVoltagePerCell = optionalNumber(params, "safetyFloorVoltagePerCell");
		if (eventType == CalibrationEventType.MONTHLY_INSPECTION) {
			if (dischargeMethod != null || endpointMode != null || targetDod != null || chargeCurrentLimitA != null
					|| safetyFloorVoltagePerCell != null) {
				throw new IllegalArgumentException("Inspection events must omit discharge plan fields");
			}
		} else {
			if (dischargeMethod == null) throw new IllegalArgumentException("Missing dischargeMethod");
			if (endpointMode == null) throw new IllegalArgumentException("Missing dischargeEndpointMode");
			if (eventType != CalibrationEventType.INITIAL_BASELINE && chargeCurrentLimitA != null) {
				throw new IllegalArgumentException("chargeCurrentLimitA is supported only for Initial Baseline");
			}
			if (eventType != CalibrationEventType.INITIAL_BASELINE && safetyFloorVoltagePerCell != null) {
				throw new IllegalArgumentException("safetyFloorVoltagePerCell is supported only for Initial Baseline");
			}
		}
		return this.service.create(requiredInt(params, "stringIndex"), eventType,
				DischargeCurrentDirection.parse(requiredString(params, "currentDirection")),
				number(params, "floatTemperatureCoefficientMvPerCellC",
						CalibrationEventConfiguration.DEFAULT_FLOAT_TEMPERATURE_COEFFICIENT_MV_PER_CELL_C),
				number(params, "boostTemperatureCoefficientMvPerCellC",
						CalibrationEventConfiguration.DEFAULT_BOOST_TEMPERATURE_COEFFICIENT_MV_PER_CELL_C),
				dischargeMethod, endpointMode, targetDod, optionalLong(params, "referenceCalibrationEventId"),
				principal, requiredPositiveIntOrDefault(params, "floatStableDurationMinutes",
						CalibrationEventConfiguration.DEFAULT_FLOAT_STABLE_DURATION_MINUTES),
				number(params, "floatVoltageToleranceV",
						CalibrationEventConfiguration.DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V),
				number(params, "floatCurrentMinA", CalibrationEventConfiguration.DEFAULT_FLOAT_CURRENT_MIN_A),
				number(params, "floatCurrentMaxA", CalibrationEventConfiguration.DEFAULT_FLOAT_CURRENT_MAX_A),
				chargeCurrentLimitA, safetyFloorVoltagePerCell);
	}

	static DeleteDraftCommand deleteDraftCommand(JsonObject params) {
		return new DeleteDraftCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "expectedEventVersion"));
	}

	private static void rejectFixedCalculationPolicy(JsonObject params) {
		for (var field : List.of("kcTemperatureMethod", "kcTableVersion", "referenceTemperatureC")) {
			if (params.has(field)) {
				throw new IllegalArgumentException(field + " is fixed server calculation policy and must be omitted");
			}
		}
	}

	private AuthenticatedPrincipal authenticate(JsonObject params) {
		if (params == null || !params.has("token") || !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString()
				|| params.getAsJsonPrimitive("token").getAsString().isBlank()) {
			throw new SecurityException("BMS session is invalid or expired");
		}
		return this.authentication.authenticateToken(params.get("token").getAsString().trim())
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
	}

	static ExecuteActionCommand actionCommand(JsonObject params) {
		return new ExecuteActionCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredEnum(params, "actionCode", ActionCode.class),
				requiredLong(params, "expectedEventVersion"), optionalString(params, "reason"),
				optionalStringList(params, "acknowledgedEvidence"));
	}

	static RedoStageCommand redoStageCommand(JsonObject params) {
		return new RedoStageCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "expectedEventVersion"),
				requiredString(params, "reason"));
	}

	static StartProcessCommand startProcessCommand(JsonObject params) {
		return new StartProcessCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "calibrationProcessId"),
				requiredLong(params, "expectedEventVersion"));
	}

	static CompleteProcessCommand completeProcessCommand(JsonObject params) {
		return new CompleteProcessCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "calibrationProcessId"),
				requiredLong(params, "expectedEventVersion"), optionalString(params, "reason"));
	}

	static ConfirmDischargeStoppedCommand confirmDischargeStoppedCommand(JsonObject params) {
		return new ConfirmDischargeStoppedCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "calibrationProcessId"),
				requiredLong(params, "expectedEventVersion"));
	}

	static ConfigureDemoTimeScaleCommand configureDemoTimeScaleCommand(JsonObject params) {
		return new ConfigureDemoTimeScaleCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "expectedEventVersion"),
				requiredInt(params, "demoTimeScaleFactor"));
	}

	static ConfigureDischargeSafetyFloorCommand configureDischargeSafetyFloorCommand(JsonObject params) {
		return new ConfigureDischargeSafetyFloorCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "expectedEventVersion"),
				requiredPositiveNumber(params, "safetyFloorVoltagePerCell"));
	}

	static ConfigureDischargeMonitorCommand configureDischargeMonitorCommand(JsonObject params) {
		return new ConfigureDischargeMonitorCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "expectedEventVersion"),
				requiredPositiveNumber(params, "safetyFloorVoltagePerCell"),
				requiredPositiveInt(params, CalibrationEventConfiguration.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY));
	}

	static ConfigureFloatStablePolicyCommand configureFloatStablePolicyCommand(JsonObject params) {
		return new ConfigureFloatStablePolicyCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "expectedEventVersion"),
				requiredPositiveInt(params, "floatStableDurationMinutes"),
				requiredPositiveNumber(params, "floatVoltageToleranceV"),
				requiredNonNegativeNumber(params, "floatCurrentMinA"),
				requiredNonNegativeNumber(params, "floatCurrentMaxA"));
	}

	static ConfigureActiveFloatCurrentRangeCommand activeFloatCurrentRangeCommand(JsonObject params) {
		return new ConfigureActiveFloatCurrentRangeCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "calibrationProcessId"),
				requiredInt(params, "processAttemptNumber"), requiredLong(params, "expectedEventVersion"),
				requiredNonNegativeNumber(params, "floatCurrentMinA"),
				requiredNonNegativeNumber(params, "floatCurrentMaxA"), requiredString(params, "reason"));
	}

	static ConfigureActiveVocDeadbandCommand activeVocDeadbandCommand(JsonObject params) {
		return new ConfigureActiveVocDeadbandCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "calibrationProcessId"),
				requiredInt(params, "processAttemptNumber"), requiredLong(params, "expectedEventVersion"),
				requiredNonNegativeNumber(params, "vocCurrentDeadbandA"), requiredString(params, "reason"));
	}

	static ManualEvidenceCommand evidenceCommand(JsonObject params) {
		return new ManualEvidenceCommand(requiredLong(params, "calibrationEventId"),
				requiredLong(params, "calibrationStageId"), requiredLong(params, "expectedEventVersion"),
				requiredString(params, "evidenceType"), requiredString(params, "status"),
				optionalNumber(params, "numericValue"), optionalString(params, "textValue"),
				optionalString(params, "unit"), optionalString(params, "instrumentId"),
				optionalNumber(params, "instrumentResolution"), optionalNumber(params, "instrumentAccuracy"),
				optionalString(params, "calibrationReference"), optionalString(params, "note"),
				optionalString(params, "externalReference"), optionalLong(params, "calibrationSampleId"));
	}

	static JsonObject detail(EventDetail value) {
		var json = summary(value.event());
		json.add("configuration", configuration(value.configuration()));
		add(json, "referenceCalibrationEventId", value.referenceCalibrationEventId());
		json.add("blockers", strings(value.blockers()));
		json.add("missingEvidence", strings(value.missingEvidence()));
		var stages = new JsonArray();
		value.stages().forEach(stage -> stages.add(stage(stage)));
		json.add("stages", stages);
		var processes = new JsonArray();
		value.processes().forEach(process -> processes.add(process(process)));
		json.add("processes", processes);
		json.add("referenceCurve", value.referenceCurve() == null ? JsonNull.INSTANCE
				: referenceCurve(value.referenceCurve()));
		var actions = new JsonArray();
		value.allowedActions().forEach(action -> actions.add(action(action)));
		json.add("allowedActions", actions);
		return json;
	}

	private static JsonObject referenceCurve(ReferenceCurve value) {
		var json = new JsonObject();
		json.addProperty("curveVersionId", value.curveVersionId());
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("stringId", value.stringId().toString());
		json.addProperty("calibrationStageId", value.calibrationStageId());
		json.addProperty("calibrationProcessId", value.calibrationProcessId());
		json.addProperty("processAttemptNumber", value.processAttemptNumber());
		json.addProperty("curveSchemaVersion", value.curveSchemaVersion());
		json.addProperty("capacityReferenceAh", value.capacityReferenceAh());
		json.addProperty("stringSafeUsableCapacityAh", value.stringSafeUsableCapacityAh());
		json.addProperty("allCellsEndpointAh", value.allCellsEndpointAh());
		json.addProperty("cellCount", value.cellCount());
		json.addProperty("pointCount", value.pointCount());
		json.addProperty("contentHash", value.contentHash());
		add(json, "publishedAt", value.publishedAt());
		json.addProperty("activeBaseline", value.activeBaseline());
		return json;
	}

	private static JsonObject curvePointPage(CurvePointPage value) {
		var json = new JsonObject();
		json.add("referenceCurve", referenceCurve(value.referenceCurve()));
		json.addProperty("offset", value.offset());
		json.addProperty("limit", value.limit());
		json.addProperty("total", value.total());
		var points = new JsonArray();
		value.points().forEach(point -> points.add(curvePoint(point)));
		json.add("points", points);
		return json;
	}

	private static JsonObject curvePoint(CurvePoint value) {
		var json = new JsonObject();
		json.addProperty("curveVersionId", value.curveVersionId());
		json.addProperty("cellIndex", value.cellIndex());
		json.addProperty("pointOrder", value.pointOrder());
		json.addProperty("calibrationSampleId", value.calibrationSampleId());
		json.addProperty("milestonePercent", value.milestonePercent());
		json.addProperty("normalizedActualDodPercent", value.normalizedActualDodPercent());
		json.addProperty("dischargedAh", value.dischargedAh());
		json.addProperty("cellVoltageV", value.cellVoltageV());
		add(json, "cellTemperatureC", value.cellTemperatureC());
		add(json, "cellResistanceUohm", value.cellResistanceUohm());
		add(json, "cellSocPct", value.cellSocPct());
		add(json, "cellSohPct", value.cellSohPct());
		add(json, "stringVoltageV", value.stringVoltageV());
		add(json, "averageVoltageV", value.averageVoltageV());
		json.addProperty("quality", value.quality());
		json.addProperty("usable", value.usable());
		return json;
	}

	private static JsonObject process(Process value) {
		var json = new JsonObject();
		json.addProperty("calibrationProcessId", value.id());
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("calibrationStageId", value.calibrationStageId());
		json.addProperty("order", value.order());
		json.addProperty("type", value.type());
		json.addProperty("attemptNumber", value.attemptNumber());
		json.addProperty("state", value.state().name());
		json.addProperty("samplingPolicy", value.samplingPolicy());
		json.add("configuration", copyOrNull(value.configuration()));
		json.add("runtime", copyOrNull(value.runtime()));
		json.add("dischargeRuntime", copyOrNull(value.dischargeRuntime()));
		// Deprecated one-release compatibility envelope. New clients must use the
		// three authoritative fields above.
		json.add("properties", copyOrNull(value.properties()));
		add(json, "startedAt", value.startedAt());
		add(json, "completedAt", value.completedAt());
		return json;
	}

	private static JsonObject summary(EventSummary value) {
		var json = new JsonObject();
		json.addProperty("calibrationEventId", value.id());
		json.addProperty("stringId", value.stringId().toString());
		json.addProperty("eventType", value.eventType().name());
		json.addProperty("status", value.status().name());
		add(json, "currentStageId", value.currentStageId());
		json.addProperty("eventVersion", value.eventVersion());
		json.addProperty("measurementRevision", value.measurementRevision());
		add(json, "createdBy", value.createdBy());
		add(json, "createdByName", value.createdByName());
		add(json, "createdAt", value.createdAt());
		add(json, "closedAt", value.closedAt());
		json.addProperty("baselineApproved", value.baselineApprovedAt() != null);
		add(json, "baselineApprovedAt", value.baselineApprovedAt());
		add(json, "baselineApprovedBy", value.baselineApprovedBy());
		add(json, "baselineApprovedByName", value.baselineApprovedByName());
		return json;
	}

	private static JsonObject configuration(CalibrationEventConfiguration value) {
		var json = new JsonObject();
		json.addProperty("eventType", value.eventType().name());
		json.addProperty("stringKey", value.stringKey());
		json.addProperty("cellCount", value.cellCount());
		json.addProperty("ratedCapacityAh", value.ratedCapacityAh());
		json.addProperty("capacityReferenceAh", value.capacityReferenceAh());
		add(json, "referenceBaselineAh", value.referenceBaselineAh());
		json.addProperty("cutoffVoltagePerCell", value.cutoffVoltagePerCell());
		json.addProperty("safetyFloorVoltagePerCell", value.safetyFloorVoltagePerCell());
		json.addProperty("floatVoltagePerCell", value.floatVoltagePerCell());
		json.addProperty("boostVoltagePerCell", value.boostVoltagePerCell());
		json.addProperty("currentDirection", value.currentDirection().name());
		json.addProperty("mappingVersion", value.mappingVersion());
		json.addProperty("floatTemperatureCoefficientMvPerCellC", value.floatTemperatureCoefficientMvPerCellC());
		json.addProperty("boostTemperatureCoefficientMvPerCellC", value.boostTemperatureCoefficientMvPerCellC());
		json.addProperty("currentDeadbandA", value.currentDeadbandA());
		json.addProperty("sampleIntervalSeconds", value.sampleIntervalSeconds());
		json.addProperty("snapshotFreshnessSeconds", value.snapshotFreshnessSeconds());
		add(json, "dischargeMethod", value.dischargeMethod() == null ? null : value.dischargeMethod().name());
		add(json, "dischargeEndpointMode",
				value.dischargeEndpointMode() == null ? null : value.dischargeEndpointMode().name());
		add(json, "targetDodPercent", value.targetDodPercent());
		json.addProperty("initialFloatCurrentLimitA", value.initialFloatCurrentLimitA());
		json.addProperty("periodicFloatCurrentLimitA", value.periodicFloatCurrentLimitA());
		json.addProperty("floatOvercurrentReferenceA", value.floatOvercurrentReferenceA());
		json.addProperty("dischargeVoltageFloorPolicy", value.dischargeVoltageFloorPolicy());
		json.addProperty("floatStableDurationMinutes", value.floatStableDurationMinutes());
		json.addProperty("floatVoltageToleranceV", value.floatVoltageToleranceV());
		json.addProperty("floatCurrentMinA", value.floatCurrentMinA());
		json.addProperty("floatCurrentMaxA", value.floatCurrentMaxA());
		json.addProperty("chargeCurrentLimitA", value.chargeCurrentLimitA());
		return json;
	}

	private static JsonObject stage(Stage value) {
		var json = new JsonObject();
		json.addProperty("calibrationStageId", value.id());
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("order", value.order());
		json.addProperty("type", value.type().name());
		json.addProperty("state", value.state().name());
		json.add("properties", copyOrNull(value.properties()));
		add(json, "result", value.result());
		add(json, "blockerCode", value.blockerCode());
		json.add("blockerDetail", copyOrNull(value.blockerDetail()));
		add(json, "startedAt", value.startedAt());
		add(json, "completedAt", value.completedAt());
		add(json, "completedBy", value.completedBy());
		add(json, "acceptedAction", value.acceptedAction());
		add(json, "acceptedReason", value.acceptedReason());
		add(json, "acceptedActor", value.acceptedActor());
		add(json, "acceptedAt", value.acceptedAt());
		json.add("acknowledgedGaps", strings(value.acknowledgedGaps()));
		return json;
	}

	private static JsonObject action(AllowedAction value) {
		var json = new JsonObject();
		json.addProperty("actionCode", value.code().name());
		json.addProperty("label", value.label());
		json.addProperty("intent", value.intent().name());
		json.addProperty("enabled", value.enabled());
		add(json, "disabledReason", value.disabledReason());
		json.addProperty("requiresReason", value.requiresReason());
		json.addProperty("requiresAcknowledgement", value.requiresAcknowledgement());
		json.add("acknowledgementItems", strings(value.acknowledgementItems()));
		return json;
	}

	private static JsonObject history(EventHistory value) {
		var json = new JsonObject();
		var transitions = new JsonArray();
		value.transitions().forEach(item -> transitions.add(transition(item)));
		json.add("transitions", transitions);
		var evidence = new JsonArray();
		value.evidence().forEach(item -> evidence.add(evidence(item)));
		json.add("evidence", evidence);
		json.add("report", value.report() == null ? JsonNull.INSTANCE : report(value.report()));
		return json;
	}

	private static JsonObject transition(Transition value) {
		var json = new JsonObject();
		json.addProperty("transitionId", value.id());
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("calibrationStageId", value.calibrationStageId());
		add(json, "fromState", value.fromState());
		json.addProperty("toState", value.toState());
		json.addProperty("action", value.action());
		add(json, "reason", value.reason());
		add(json, "actorId", value.actorId());
		add(json, "actorName", value.actorName());
		json.add("evidence", copyOrNull(value.evidence()));
		json.addProperty("resultingEventVersion", value.resultingEventVersion());
		json.addProperty("transitionOrder", value.transitionOrder());
		add(json, "occurredAt", value.occurredAt());
		return json;
	}

	private static JsonObject evidence(ManualEvidence value) {
		var json = new JsonObject();
		json.addProperty("evidenceId", value.id());
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("calibrationStageId", value.calibrationStageId());
		json.addProperty("evidenceType", value.evidenceType());
		json.addProperty("status", value.status());
		add(json, "numericValue", value.numericValue());
		add(json, "textValue", value.textValue());
		add(json, "unit", value.unit());
		add(json, "instrumentId", value.instrumentId());
		add(json, "instrumentResolution", value.instrumentResolution());
		add(json, "instrumentAccuracy", value.instrumentAccuracy());
		add(json, "calibrationReference", value.calibrationReference());
		add(json, "actorId", value.actorId());
		add(json, "actorName", value.actorName());
		add(json, "note", value.note());
		add(json, "externalReference", value.externalReference());
		add(json, "calibrationSampleId", value.calibrationSampleId());
		add(json, "recordedAt", value.recordedAt());
		return json;
	}

	private static JsonObject report(Report value) {
		var json = new JsonObject();
		json.addProperty("reportSchemaVersion", value.reportSchemaVersion());
		json.add("data", copyOrNull(value.data()));
		add(json, "generatedAt", value.generatedAt());
		add(json, "generatedBy", value.generatedBy());
		json.addProperty("contentHash", value.contentHash());
		return json;
	}

	static JsonObject sample(Sample value) {
		var json = new JsonObject();
		json.addProperty("sampleId", value.id());
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("calibrationStageId", value.calibrationStageId());
		add(json, "calibrationProcessId", value.calibrationProcessId());
		add(json, "processAttemptNumber", value.processAttemptNumber());
		json.addProperty("snapshotGeneration", value.snapshotGeneration().toString());
		json.addProperty("snapshotSequence", value.snapshotSequence());
		add(json, "measurementTime", value.measurementTime());
		json.addProperty("persistenceReason", value.persistenceReason());
		json.addProperty("quality", value.quality());
		add(json, "blockerReason", value.blockerReason());
		add(json, "rawCurrentA", value.rawCurrentA());
		add(json, "domainCurrentA", value.domainCurrentA());
		add(json, "currentDirection", value.currentDirection());
		add(json, "mappingVersion", value.mappingVersion());
		add(json, "currentMeasuredAt", value.currentMeasuredAt());
		add(json, "currentQuality", value.currentQuality());
		add(json, "stringVoltageV", value.stringVoltageV());
		add(json, "midpointVoltageV", value.midpointVoltageV());
		add(json, "averageVoltageV", value.averageVoltageV());
		add(json, "ambientTemperatureC", value.ambientTemperatureC());
		add(json, "averageCellTemperatureC", value.averageCellTemperatureC());
		add(json, "deltaSeconds", value.deltaSeconds());
		add(json, "deltaAh", value.deltaAh());
		add(json, "rawUsedAh", value.rawUsedAh());
		add(json, "representativeDischargeTemperatureC", value.representativeDischargeTemperatureC());
		add(json, "temperatureFactorKc", value.temperatureFactorKc());
		add(json, "correctedUsedAh", value.correctedUsedAh());
		add(json, "socPct", value.socPct());
		add(json, "socMethod", value.socMethod());
		add(json, "dodPct", value.dodPct());
		add(json, "floatTargetVoltageV", value.floatTargetVoltageV());
		add(json, "boostTargetVoltageV", value.boostTargetVoltageV());
		add(json, "createdAt", value.createdAt());
		var cells = new JsonArray();
		value.cells().forEach(cell -> cells.add(cell(cell)));
		json.add("cells", cells);
		return json;
	}

	private static JsonObject cell(CellSample value) {
		var json = new JsonObject();
		json.addProperty("cellIndex", value.cellIndex());
		add(json, "voltageV", value.voltageV());
		add(json, "temperatureC", value.temperatureC());
		add(json, "resistanceUohm", value.resistanceUohm());
		add(json, "socPct", value.socPct());
		add(json, "socMethod", value.socMethod());
		add(json, "sohPct", value.sohPct());
		add(json, "sohMethod", value.sohMethod());
		json.addProperty("quality", value.quality());
		add(json, "availabilityWarning", value.availabilityWarning());
		return json;
	}

	private static JsonArray strings(List<String> values) {
		var array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static JsonElement copyOrNull(JsonElement value) {
		return value == null || value.isJsonNull() ? JsonNull.INSTANCE : value.deepCopy();
	}

	private static String requiredString(JsonObject params, String key) {
		var value = optionalString(params, key);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
		return value.trim();
	}

	private static String optionalString(JsonObject params, String key) {
		if (params == null || !params.has(key) || params.get(key).isJsonNull()) return null;
		var value = params.get(key);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(key + " must be a string");
		}
		return value.getAsString();
	}

	private static long requiredLong(JsonObject params, String key) {
		if (params == null || !params.has(key) || !params.get(key).isJsonPrimitive()
				|| !params.getAsJsonPrimitive(key).isNumber()) {
			throw new IllegalArgumentException("Missing or invalid " + key);
		}
		final BigDecimal value;
		try {
			value = params.getAsJsonPrimitive(key).getAsBigDecimal().stripTrailingZeros();
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + " must be a non-negative integer", e);
		}
		if (value.signum() < 0 || value.scale() > 0 || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
			throw new IllegalArgumentException(key + " must be a non-negative integer");
		}
		return value.longValueExact();
	}

	private static Long optionalLong(JsonObject params, String key) {
		if (params == null || !params.has(key) || params.get(key).isJsonNull()) return null;
		return requiredLong(params, key);
	}

	private static int requiredInt(JsonObject params, String key) {
		var value = requiredLong(params, key);
		if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is outside the integer range");
		return (int) value;
	}

	private static int requiredPositiveIntOrDefault(JsonObject params, String key, int fallback) {
		if (params == null || !params.has(key) || params.get(key).isJsonNull()) return fallback;
		var value = requiredInt(params, key);
		if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
		return value;
	}

	private static int requiredPositiveInt(JsonObject params, String key) {
		var value = requiredInt(params, key);
		if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
		return value;
	}

	private static Integer optionalInt(JsonObject params, String key) {
		if (params == null || !params.has(key) || params.get(key).isJsonNull()) return null;
		return requiredInt(params, key);
	}

	private static double requiredNumber(JsonObject params, String key) {
		if (params == null || !params.has(key) || !params.get(key).isJsonPrimitive()
				|| !params.getAsJsonPrimitive(key).isNumber()) {
			throw new IllegalArgumentException("Missing or invalid " + key);
		}
		try {
			var value = params.getAsJsonPrimitive(key).getAsDouble();
			if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
			return value;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + " must be a finite number", e);
		}
	}

	private static double requiredPositiveNumber(JsonObject params, String key) {
		var value = requiredNumber(params, key);
		if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
		return value;
	}

	private static double requiredNonNegativeNumber(JsonObject params, String key) {
		var value = requiredNumber(params, key);
		if (value < 0) throw new IllegalArgumentException(key + " must be non-negative");
		return value;
	}

	private static double number(JsonObject params, String key, double fallback) {
		return params == null || !params.has(key) || params.get(key).isJsonNull() ? fallback
				: requiredNumber(params, key);
	}

	private static Double optionalNumber(JsonObject params, String key) {
		return params == null || !params.has(key) || params.get(key).isJsonNull() ? null : requiredNumber(params, key);
	}

	private static UUID optionalUuid(JsonObject params, String key) {
		var value = optionalString(params, key);
		if (value == null) return null;
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(key + " must be a UUID", e);
		}
	}

	private static <E extends Enum<E>> E requiredEnum(JsonObject params, String key, Class<E> type) {
		var value = nullableEnum(params, key, type);
		if (value == null) throw new IllegalArgumentException("Missing " + key);
		return value;
	}

	private static <E extends Enum<E>> E nullableEnum(JsonObject params, String key, Class<E> type) {
		var value = optionalString(params, key);
		if (value == null) return null;
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid " + key + ": " + value, e);
		}
	}

	private static List<String> optionalStringList(JsonObject params, String key) {
		if (params == null || !params.has(key) || params.get(key).isJsonNull()) return List.of();
		if (!params.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array of strings");
		var result = new ArrayList<String>();
		for (var item : params.getAsJsonArray(key)) {
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
					|| item.getAsString().isBlank()) {
				throw new IllegalArgumentException(key + " must contain non-blank strings");
			}
			result.add(item.getAsString().trim());
		}
		return List.copyOf(result);
	}

	private static void add(JsonObject json, String key, String value) {
		json.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
	}

	private static void add(JsonObject json, String key, Number value) {
		json.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
	}

	private static void add(JsonObject json, String key, UUID value) {
		add(json, key, value == null ? null : value.toString());
	}

	private static void add(JsonObject json, String key, Instant value) {
		add(json, key, value == null ? null : value.toString());
	}
}
