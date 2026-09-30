package io.openems.edge.bms.endpoints.monitoring;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService.AlarmOccurrence;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService.AlarmTransition;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Authenticated API projection of the current atomic monitoring frame. */
@Component(name = "io.openems.edge.bms.api.monitoring.BmsMonitoringJsonApi", property = "entry=edge.websocket.root")
public class BmsMonitoringJsonApi implements JsonApi {

	public static final String GET = "bms.monitoring.get";
	public static final String LIST_ALARMS = "bms.monitoring.alarms.list";
	public static final String ACKNOWLEDGE_ALARM = "bms.monitoring.alarms.acknowledge";
	public static final int CONTRACT_VERSION = 2;

	private final CopyOnWriteArrayList<BmsMonitoringSnapshotProvider> providers = new CopyOnWriteArrayList<>();

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private MonitoringAlarmService alarms;

	@Reference(service = BmsMonitoringSnapshotProvider.class, cardinality = ReferenceCardinality.MULTIPLE,
			policy = ReferencePolicy.DYNAMIC)
	protected void addMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.providers.addIfAbsent(provider);
	}

	protected void removeMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.providers.remove(provider);
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET, call -> {
			var params = call.getRequest().getParams();
			authorizeRead(params);
			var stringKey = requiredString(params, "stringKey");
			var provider = this.provider(stringKey);
			var result = new JsonObject();
			result.addProperty("contractVersion", CONTRACT_VERSION);
			var frame = provider.latestFrame();
			if (frame != null && !stringKey.equals(frame.stringKey())) {
				throw new IllegalStateException("Monitoring provider for " + stringKey
						+ " published a frame for " + frame.stringKey());
			}
			result.add("frame", frame == null ? JsonNull.INSTANCE : frame(frame));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest(LIST_ALARMS, call -> {
			var params = call.getRequest().getParams();
			authorizeRead(params);
			var values = this.alarms.listWithCheckpoint(optionalString(params, "stringKey"),
					optionalBoolean(params, "includeCleared", false), optionalInt(params, "offset", 0),
					optionalInt(params, "limit", 100));
			var result = new JsonObject();
			var array = new JsonArray();
			values.alarms().forEach(value -> array.add(occurrence(value)));
			result.add("alarms", array);
			result.add("processorCheckpoint", processorCheckpoint(values.processorCheckpoint()));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest(ACKNOWLEDGE_ALARM, call -> {
			var params = call.getRequest().getParams();
			var principal = authorizeOperator(params);
			var occurrenceId = requiredPositiveLong(params, "occurrenceId");
			var acknowledgement = this.alarms.acknowledge(occurrenceId, principal.username(), Instant.now());
			var result = new JsonObject();
			result.addProperty("occurrenceId", occurrenceId);
			result.addProperty("result", acknowledgement.name());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
	}

	private BmsMonitoringSnapshotProvider provider(String stringKey) {
		BmsMonitoringSnapshotProvider match = null;
		for (var provider : this.providers) {
			if (!stringKey.equals(provider.stringKey())) continue;
			if (match != null && match != provider) {
				throw new IllegalStateException("Multiple monitoring providers for " + stringKey);
			}
			match = provider;
		}
		if (match == null) throw new IllegalArgumentException("No monitoring provider for " + stringKey);
		return match;
	}

	private AuthenticatedPrincipal authorizeRead(JsonObject params) {
		var principal = authenticate(params);
		if (!(principal.hasRole(PlatformRole.BMS_VIEWER) || principal.hasRole(PlatformRole.BMS_OPERATOR))) {
			throw new SecurityException("Insufficient role for BMS monitoring read");
		}
		return principal;
	}

	private AuthenticatedPrincipal authorizeOperator(JsonObject params) {
		var principal = authenticate(params);
		if (!principal.hasRole(PlatformRole.BMS_OPERATOR)) {
			throw new SecurityException("Insufficient role for alarm acknowledgement");
		}
		return principal;
	}

	private AuthenticatedPrincipal authenticate(JsonObject params) {
		if (params == null || !params.has("token") || params.get("token").isJsonNull()
				|| !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString()) {
			throw new SecurityException("BMS session token is required");
		}
		var token = params.get("token").getAsString().trim();
		if (token.isBlank()) throw new SecurityException("BMS session token is required");
		return this.authentication.authenticateToken(token)
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
	}

	public static JsonObject frame(BmsMonitoringSnapshot value) {
		var json = new JsonObject();
		json.addProperty("stringKey", value.stringKey());
		json.addProperty("snapshotGeneration", value.snapshotGeneration().toString());
		json.addProperty("snapshotSequence", value.snapshotSequence());
		json.addProperty("sourceGeneration", value.sourceGeneration().toString());
		json.addProperty("sourceSequence", value.sourceSequence());
		json.addProperty("publicationGeneration", value.publicationGeneration().toString());
		json.addProperty("publicationRevision", value.publicationRevision());
		json.addProperty("referenceAvailable", value.referenceAvailable());
		var evaluations = new JsonObject();
		value.alarmEvaluations().forEach((alarm, evaluation) -> evaluations.addProperty(alarm.name(), evaluation.name()));
		json.add("alarmEvaluations", evaluations);
		json.add("socEstimator", estimator(value.socEstimator()));
		json.add("sohEstimator", estimator(value.sohEstimator()));
		json.addProperty("capturedAt", value.capturedAt().toString());
		json.addProperty("capturedAtEpochMs", value.capturedAt().toEpochMilli());
		add(json, "currentMeasuredAtEpochMs", value.currentMeasuredAt());
		json.addProperty("currentQuality", value.currentQuality().name());
		json.addProperty("stale", value.stale());
		json.add("reference", reference(value.reference()));
		json.add("measurements", measurements(value.string()));
		var cells = new JsonArray();
		value.cells().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
				.forEach(entry -> cells.add(cell(entry.getValue())));
		json.add("cells", cells);
		json.add("thresholds", thresholds(value.thresholds()));
		json.add("activeCalibration", activeCalibration(value.activeCalibration()));
		json.add("operatingMode", operatingMode(value.operatingMode()));
		json.addProperty("currentDirectionMapping", value.currentDirectionMapping().name());
		json.addProperty("alarmMask", value.alarmMask());
		var active = new JsonArray();
		value.activeAlarms().stream().sorted(java.util.Comparator.comparingInt(BmsMonitoringSnapshot.Alarm::bit))
				.forEach(alarm -> active.add(alarm.name()));
		json.add("activeAlarms", active);
		var details = new JsonArray();
		value.alarmDetails().forEach(detail -> details.add(alarmDetail(detail)));
		json.add("alarmDetails", details);
		add(json, "qualityWarning", value.qualityWarning());
		return json;
	}

	private static JsonObject reference(BmsMonitoringSnapshot.ReferenceIdentity value) {
		if (value == null) return null;
		var json = new JsonObject();
		json.addProperty("calibrationEventId", value.calibrationEventId());
		json.addProperty("curveVersionId", value.curveVersionId());
		json.addProperty("calibrationProcessId", value.calibrationProcessId());
		json.addProperty("processAttemptNumber", value.processAttemptNumber());
		json.addProperty("recoveryFloatSampleId", value.recoveryFloatSampleId());
		json.addProperty("vocSampleId", value.vocSampleId());
		json.addProperty("contentHash", value.contentHash());
		return json;
	}

	private static JsonObject measurements(BmsMonitoringSnapshot.StringMeasurements value) {
		if (value == null) return null;
		var json = new JsonObject();
		add(json, "batteryVoltageV", value.batteryVoltageV());
		add(json, "midpointVoltageV", value.midpointVoltageV());
		add(json, "batteryCurrentA", value.batteryCurrentA());
		add(json, "batteryPowerW", value.batteryPowerW());
		add(json, "floatCurrentA", value.floatCurrentA());
		add(json, "minCellVoltageV", value.minCellVoltageV());
		add(json, "maxCellVoltageV", value.maxCellVoltageV());
		add(json, "averageCellVoltageV", value.averageCellVoltageV());
		add(json, "maxCellResistanceUohm", value.maxCellResistanceUohm());
		add(json, "averageCellResistanceUohm", value.averageCellResistanceUohm());
		add(json, "ambientTemperatureC", value.ambientTemperatureC());
		add(json, "maxCellTemperatureC", value.maxCellTemperatureC());
		add(json, "averageCellTemperatureC", value.averageCellTemperatureC());
		add(json, "batterySocPct", value.batterySocPct());
		add(json, "remainingDischargeHours", value.remainingDischargeHours());
		add(json, "batterySohPct", value.batterySohPct());
		add(json, "minimumCellSohPct", value.minimumCellSohPct());
		return json;
	}

	private static JsonObject cell(BmsMonitoringSnapshot.CellMeasurements value) {
		var json = new JsonObject();
		json.addProperty("cellIndex", value.cellIndex());
		add(json, "voltageV", value.voltageV());
		add(json, "resistanceUohm", value.resistanceUohm());
		add(json, "resistanceAlarmThresholdUohm", value.resistanceAlarmThresholdUohm());
		add(json, "temperatureC", value.temperatureC());
		add(json, "socPct", value.socPct());
		add(json, "sohPct", value.sohPct());
		add(json, "availabilityWarning", value.availabilityWarning());
		json.add("socEstimator", estimator(value.socEstimator()));
		json.add("sohEstimator", estimator(value.sohEstimator()));
		return json;
	}

	private static JsonObject estimator(EstimatorProvenance value) {
		if (value == null) return null;
		var json = new JsonObject();
		add(json, "method", value.method());
		add(json, "state", value.state());
		json.addProperty("quality", value.quality().name());
		add(json, "note", value.note());
		var reference = value.reference();
		if (reference == null) {
			json.add("reference", JsonNull.INSTANCE);
		} else {
			var identity = new JsonObject();
			add(identity, "calibrationEventId", reference.calibrationEventId());
			add(identity, "curveVersionId", reference.curveVersionId());
			add(identity, "calibrationProcessId", reference.calibrationProcessId());
			add(identity, "processAttemptNumber", reference.processAttemptNumber());
			add(identity, "contentHash", reference.contentHash());
			json.add("reference", identity);
		}
		return json;
	}

	private static JsonObject thresholds(BmsMonitoringSnapshot.AppliedThresholds value) {
		if (value == null) return null;
		var json = new JsonObject();
		add(json, "lowCellDeviationFraction", value.lowCellDeviationFraction());
		add(json, "highCellDeviationFraction", value.highCellDeviationFraction());
		add(json, "batteryVoltageLowV", value.batteryVoltageLowV());
		add(json, "batteryVoltageHighV", value.batteryVoltageHighV());
		add(json, "resistanceBaselineMultiplier", value.resistanceBaselineMultiplier());
		add(json, "cellTemperatureAboveAverageC", value.cellTemperatureAboveAverageC());
		add(json, "cellTemperatureAboveAmbientC", value.cellTemperatureAboveAmbientC());
		add(json, "floatCurrentBaselineMultiplier", value.floatCurrentBaselineMultiplier());
		add(json, "floatChargeOvercurrentA", value.floatChargeOvercurrentA());
		add(json, "chargeOvercurrentA", value.chargeOvercurrentA());
		add(json, "lowSocPct", value.lowSocPct());
		add(json, "highAmbientTemperatureC", value.highAmbientTemperatureC());
		add(json, "midpointImbalanceV", value.midpointImbalanceV());
		return json;
	}

	private static JsonObject activeCalibration(ActiveCalibrationContext value) {
		if (value == null) return null;
		var json = new JsonObject();
		json.addProperty("active", value.active());
		add(json, "calibrationEventId", value.calibrationEventId());
		add(json, "calibrationStageId", value.calibrationStageId());
		add(json, "calibrationProcessId", value.calibrationProcessId());
		add(json, "eventType", value.eventType());
		add(json, "stageType", value.stageType());
		add(json, "processType", value.processType());
		add(json, "chargeCurrentLimitA", value.chargeCurrentLimitA());
		return json;
	}

	private static JsonObject operatingMode(BmsMonitoringSnapshot.ModeEvidence value) {
		if (value == null) return null;
		var json = new JsonObject();
		json.addProperty("mode", value.mode().name());
		json.addProperty("source", value.source().name());
		json.addProperty("quality", value.quality().name());
		json.addProperty("resolvedAt", value.resolvedAt().toString());
		json.addProperty("snapshotGeneration", value.snapshotGeneration().toString());
		json.addProperty("snapshotSequence", value.snapshotSequence());
		add(json, "reason", value.reason());
		return json;
	}

	private static JsonObject alarmDetail(BmsMonitoringSnapshot.AlarmDetail value) {
		var json = new JsonObject();
		json.addProperty("alarm", value.alarm().name());
		add(json, "cellIndex", value.cellIndex());
		add(json, "observedValue", value.observedValue());
		add(json, "thresholdValue", value.thresholdValue());
		add(json, "reason", value.reason());
		return json;
	}

	private static JsonObject occurrence(AlarmOccurrence value) {
		var json = new JsonObject();
		json.addProperty("id", value.id());
		json.addProperty("stringKey", value.stringKey());
		json.addProperty("alarm", value.alarm().name());
		json.addProperty("subjectKey", value.subjectKey());
		add(json, "cellIndex", value.cellIndex());
		json.addProperty("status", value.status());
		json.addProperty("conditionActive", value.conditionActive());
		add(json, "raisedAt", value.raisedAt());
		add(json, "conditionClearedAt", value.conditionClearedAt());
		add(json, "clearedAt", value.clearedAt());
		add(json, "acknowledgedAt", value.acknowledgedAt());
		add(json, "acknowledgedBy", value.acknowledgedBy());
		add(json, "triggerObservedValue", value.triggerObservedValue());
		add(json, "triggerThresholdValue", value.triggerThresholdValue());
		add(json, "triggerReason", value.triggerReason());
		json.addProperty("triggerGeneration", value.triggerGeneration().toString());
		json.addProperty("triggerSequence", value.triggerSequence());
		json.addProperty("triggerCapturedAt", value.triggerCapturedAt().toString());
		json.add("reference", reference(value.reference()));
		json.add("activeCalibration", activeCalibration(value.activeCalibration()));
		json.addProperty("policyVersion", value.policyVersion());
		json.addProperty("raiseSamples", value.raiseSamples());
		json.addProperty("clearSamples", value.clearSamples());
		json.addProperty("hysteresisValue", value.hysteresisValue());
		json.addProperty("hysteresisUnit", value.hysteresisUnit());
		json.addProperty("latching", value.latching());
		var transitions = new JsonArray();
		value.transitions().forEach(transition -> transitions.add(transition(transition)));
		json.add("transitions", transitions);
		return json;
	}

	private static JsonObject processorCheckpoint(MonitoringAlarmService.ProcessorCheckpoint value) {
		var json = new JsonObject();
		add(json, "publicationGeneration", value.publicationGeneration() == null ? null
				: value.publicationGeneration().toString());
		add(json, "publicationRevision", value.publicationRevision());
		add(json, "processedAt", value.processedAt());
		return json;
	}

	private static JsonObject transition(AlarmTransition value) {
		var json = new JsonObject();
		json.addProperty("id", value.id());
		json.addProperty("type", value.type());
		json.addProperty("transitionedAt", value.transitionedAt().toString());
		add(json, "actor", value.actor());
		add(json, "observedValue", value.observedValue());
		add(json, "thresholdValue", value.thresholdValue());
		add(json, "reason", value.reason());
		json.addProperty("sourceGeneration", value.sourceGeneration().toString());
		json.addProperty("sourceSequence", value.sourceSequence());
		json.addProperty("sourceCapturedAt", value.sourceCapturedAt().toString());
		json.add("reference", reference(value.reference()));
		json.addProperty("policyVersion", value.policyVersion());
		return json;
	}

	private static void add(JsonObject json, String name, Number value) {
		if (value == null) json.add(name, JsonNull.INSTANCE); else json.addProperty(name, value);
	}

	private static void add(JsonObject json, String name, String value) {
		if (value == null) json.add(name, JsonNull.INSTANCE); else json.addProperty(name, value);
	}

	private static void add(JsonObject json, String name, Instant value) {
		if (value == null) json.add(name, JsonNull.INSTANCE); else json.addProperty(name, value.toString());
	}

	private static String requiredString(JsonObject params, String name) {
		var value = optionalString(params, name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
		return value;
	}

	private static String optionalString(JsonObject params, String name) {
		return params == null || !params.has(name) || params.get(name).isJsonNull() ? null
				: params.get(name).getAsString().trim();
	}

	private static int optionalInt(JsonObject params, String name, int fallback) {
		if (!params.has(name) || params.get(name).isJsonNull()) return fallback;
		var value = integralLong(params, name);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(name + " is outside integer range");
		}
		return (int) value;
	}

	private static boolean optionalBoolean(JsonObject params, String name, boolean fallback) {
		return !params.has(name) || params.get(name).isJsonNull() ? fallback : params.get(name).getAsBoolean();
	}

	private static long requiredPositiveLong(JsonObject params, String name) {
		if (!params.has(name) || params.get(name).isJsonNull()) throw new IllegalArgumentException("Missing " + name);
		var value = integralLong(params, name);
		if (value < 1) throw new IllegalArgumentException(name + " must be positive");
		return value;
	}

	private static long integralLong(JsonObject params, String name) {
		var element = params.get(name);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(name + " must be an integer");
		}
		try {
			return element.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			throw new IllegalArgumentException(name + " must be an integer", e);
		}
	}
}
