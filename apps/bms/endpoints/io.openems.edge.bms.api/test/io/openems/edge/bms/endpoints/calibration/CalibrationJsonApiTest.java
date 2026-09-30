package io.openems.edge.bms.endpoints.calibration;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.endpoints.calibration.CalibrationJsonApi;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeMethod;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionCode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionIntent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CellSample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CreateCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePoint;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CurvePointPage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeleteDraftCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeletedEvent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventStatus;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventSummary;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Process;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ProcessState;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ReferenceCurve;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Sample;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Stage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StageState;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import io.openems.edge.bms.service.calibration.contracts.CalibrationEventOperations;
import java.lang.reflect.Proxy;
import io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

public class CalibrationJsonApiTest {

	private static final UUID ACTOR_ID = UUID.fromString("8d2b8d6b-3de3-45e1-9e2a-a19b9462fbd0");
	private static final UUID STRING_ID = UUID.fromString("74c6264e-3913-49ce-8286-54083718295f");




	@Test
	public void createPassesSessionActorAndOnlySupportedConfigurationToContract() throws Exception {
		var service = new CaptureService();
		var api = api(service, Set.of(PlatformRole.BMS_OPERATOR));
		var params = createParams();
		params.addProperty("actorName", "spoofed");
		params.addProperty("actorId", UUID.randomUUID().toString());
		params.addProperty("ratedCapacityAh", 9999);
		params.addProperty("cellCount", 1);
		params.addProperty("chargeCurrentLimitA", 37.5);
		invoke(api, CalibrationJsonApi.CREATE, params);
		assertEquals(4, service.createArgs[0]);
		assertEquals(CalibrationEventType.INITIAL_BASELINE, service.createArgs[1]);
		assertEquals(DischargeMethod.C10, service.createArgs[5]);
		assertSame(service.sessionPrincipal, service.createArgs[9]);
		assertEquals(37.5, service.createArgs[14]);
		assertEquals(16, service.createArgs.length);
	}

	@Test
	public void authenticationRejectsMissingBlankNonStringAndInvalidTokens() throws Exception {
		var api = api(new CaptureService(), Set.of(PlatformRole.BMS_VIEWER));
		for (var params : List.of(new JsonObject(), token(" "), tokenNumber(1), token("invalid"))) {
			var exception = assertThrows(InvocationTargetException.class, () -> invokeAuthenticate(api, params));
			assertTrue(exception.getCause() instanceof SecurityException);
		}
	}

	@Test
	public void authenticatedRoutesRejectMissingSessionBeforeParsingCommandFields() throws Exception {
		var api = api(new CaptureService(), Set.of(PlatformRole.BMS_OPERATOR));
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);
		for (var method : List.of(CalibrationJsonApi.GET, CalibrationJsonApi.START,
				CalibrationJsonApi.DELETE_DRAFT,
				CalibrationJsonApi.APPEND_EVIDENCE, CalibrationJsonApi.EXECUTE_STAGE_ACTION,
				CalibrationJsonApi.START_PROCESS, CalibrationJsonApi.COMPLETE_PROCESS,
				CalibrationJsonApi.CONFIRM_DISCHARGE_STOPPED,
				CalibrationJsonApi.CONFIGURE_DEMO_TIME_SCALE, CalibrationJsonApi.CONFIGURE_DISCHARGE_MONITOR,
				CalibrationJsonApi.CONFIGURE_ACTIVE_FLOAT_CURRENT_RANGE,
				CalibrationJsonApi.CONFIGURE_ACTIVE_VOC_DEADBAND,
				CalibrationJsonApi.REDO_STAGE,
				CalibrationJsonApi.SAMPLES, CalibrationJsonApi.CURVE_POINTS, CalibrationJsonApi.HISTORY)) {
			var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest(method, new JsonObject()));
			builder.handle(call);
			assertEquals("BMS session is invalid or expired",
					call.getResponse().toJsonObject().getAsJsonObject("error").get("message").getAsString());
		}
	}

	@Test
	public void curvePointsRouteParsesFiltersAndSerializesPublishedMetadata() throws Exception {
		var service = new CaptureService();
		var api = api(service, Set.of(PlatformRole.BMS_VIEWER));
		var params = token("valid-token");
		params.addProperty("calibrationEventId", 42);
		params.addProperty("curveVersionId", 91);
		params.addProperty("cellIndex", 7);
		params.addProperty("offset", 2);
		params.addProperty("limit", 20);
		var result = invoke(api, CalibrationJsonApi.CURVE_POINTS, params);
		assertEquals(42, service.curveEventId);
		assertEquals(91L, service.curveVersionId);
		assertEquals(7, service.curveCellIndex);
		assertEquals(2, service.curveOffset);
		assertEquals(20, service.curveLimit);
		assertEquals(91, result.getAsJsonObject("referenceCurve").get("curveVersionId").getAsLong());
		assertTrue(result.getAsJsonObject("referenceCurve").get("activeBaseline").getAsBoolean());
		assertEquals(1, result.getAsJsonArray("points").size());
		assertEquals(55.5556, result.getAsJsonArray("points").get(0).getAsJsonObject()
				.get("normalizedActualDodPercent").getAsDouble(), 0.000001);
		assertTrue(result.getAsJsonArray("points").get(0).getAsJsonObject().get("cellSocPct").isJsonNull());
		assertTrue(result.getAsJsonArray("points").get(0).getAsJsonObject().get("cellSohPct").isJsonNull());
	}

	@Test
	public void deleteDraftUsesAuthenticatedVersionedCommandAndReturnsDeletedEventId() throws Exception {
		var service = new CaptureService();
		var api = api(service, Set.of(PlatformRole.BMS_OPERATOR));
		var command = token("valid-token");
		command.addProperty("calibrationEventId", 42);
		command.addProperty("expectedEventVersion", 7);

		var result = invoke(api, CalibrationJsonApi.DELETE_DRAFT, command);
		assertEquals(42, result.get("calibrationEventId").getAsLong());
		assertEquals(42, service.deletedCommand.calibrationEventId());
		assertEquals(7, service.deletedCommand.expectedEventVersion());
	}



	@Test
	public void redoStageCommandCarriesTargetVersionAndRequiredReason() {
		var params = new JsonObject();
		params.addProperty("calibrationEventId", 42);
		params.addProperty("calibrationStageId", 101);
		params.addProperty("expectedEventVersion", 7);
		params.addProperty("reason", "Repeat inspection after repair");
		var command = CalibrationJsonApi.redoStageCommand(params);
		assertEquals(42, command.calibrationEventId());
		assertEquals(101, command.calibrationStageId());
		assertEquals(7, command.expectedEventVersion());
		assertEquals("Repeat inspection after repair", command.reason());
		params.remove("reason");
		assertThrows(IllegalArgumentException.class, () -> CalibrationJsonApi.redoStageCommand(params));
	}



	@Test
	public void inspectionRejectsDischargePlanAndCreationValidatesTemperatureCoefficients() throws Exception {
		var service = new CaptureService();
		var api = api(service, Set.of(PlatformRole.BMS_OPERATOR));
		var inspection = createParams();
		inspection.addProperty("eventType", "MONTHLY_INSPECTION");
		assertRouteFailure(api, inspection, "Inspection events must omit discharge plan fields");

		var defaults = createParams();
		defaults.remove("mappingVersion");
		defaults.remove("dischargeVoltageFloorPolicy");
		invoke(api, CalibrationJsonApi.CREATE, defaults);
		assertEquals(-3.3, service.createArgs[3]);
		assertEquals(-5.0, service.createArgs[4]);
		assertNull(service.createArgs[14]); // Server owns the rated-capacity default.


		var invalidCoefficient = createParams();
		invalidCoefficient.addProperty("floatTemperatureCoefficientMvPerCellC", "not-a-number");
		assertRouteFailure(api, invalidCoefficient, "floatTemperatureCoefficientMvPerCellC");

		for (var fixedField : List.of("kcTemperatureMethod", "kcTableVersion", "referenceTemperatureC")) {
			var forbiddenPolicy = createParams();
			forbiddenPolicy.addProperty(fixedField, "client-controlled");
			assertRouteFailure(api, forbiddenPolicy, fixedField + " is fixed server calculation policy");
		}
	}

	@Test
	public void monthlyInspectionCreateAndStartUsePersistedVersionedState() throws Exception {
		var service = new CaptureService();
		var api = api(service, Set.of(PlatformRole.BMS_OPERATOR));
		var create = token("valid-token");
		create.addProperty("stringIndex", 4);
		create.addProperty("eventType", "MONTHLY_INSPECTION");
		create.addProperty("currentDirection", "NEGATIVE");

		var draft = invoke(api, CalibrationJsonApi.CREATE, create);
		assertEquals("MONTHLY_INSPECTION", draft.get("eventType").getAsString());
		assertEquals("DRAFT", draft.get("status").getAsString());
		assertEquals(0, draft.get("eventVersion").getAsLong());
		assertTrue(draft.get("currentStageId").isJsonNull());
		assertEquals(0, draft.getAsJsonArray("stages").size());
		assertNull(service.createArgs[5]);

		var start = token("valid-token");
		start.addProperty("calibrationEventId", draft.get("calibrationEventId").getAsLong());
		start.addProperty("expectedEventVersion", draft.get("eventVersion").getAsLong());
		var active = invoke(api, CalibrationJsonApi.START, start);
		assertEquals(0, service.startedWithVersion);
		assertEquals("ACTIVE", active.get("status").getAsString());
		assertEquals(1, active.get("eventVersion").getAsLong());
		assertEquals(active.getAsJsonArray("stages").get(0).getAsJsonObject().get("calibrationStageId").getAsLong(),
				active.get("currentStageId").getAsLong());
		assertEquals(List.of("PREREQUISITES", "MONTHLY_INSPECTION", "COMPLETION"),
				active.getAsJsonArray("stages").asList().stream()
						.map(value -> value.getAsJsonObject().get("type").getAsString()).toList());
	}

	@Test
	public void actionAndEvidencePayloadsAreVersionedAndDefensivelyParsed() {
		var action = new JsonObject();
		action.addProperty("calibrationEventId", 42);
		action.addProperty("calibrationStageId", 7);
		action.addProperty("actionCode", "complete_with_gaps");
		action.addProperty("expectedEventVersion", 9);
		action.addProperty("reason", "accepted gap");
		var acknowledgements = new JsonArray();
		acknowledgements.add("MIDPOINT_VOLTAGE");
		action.add("acknowledgedEvidence", acknowledgements);
		var command = CalibrationJsonApi.actionCommand(action);
		assertEquals(ActionCode.COMPLETE_WITH_GAPS, command.actionCode());
		assertEquals(9, command.expectedEventVersion());
		assertEquals(List.of("MIDPOINT_VOLTAGE"), command.acknowledgedEvidence());

		action.addProperty("expectedEventVersion", 9.5);
		assertThrows(IllegalArgumentException.class, () -> CalibrationJsonApi.actionCommand(action));
		action.addProperty("expectedEventVersion", 9);
		action.addProperty("actionCode", "ADVANCE_ANYWAY");
		assertThrows(IllegalArgumentException.class, () -> CalibrationJsonApi.actionCommand(action));

		var evidence = new JsonObject();
		evidence.addProperty("calibrationEventId", 42);
		evidence.addProperty("calibrationStageId", 7);
		evidence.addProperty("expectedEventVersion", 10);
		evidence.addProperty("evidenceType", "FLOAT_MODE_REVIEWED");
		evidence.addProperty("status", "PASS");
		evidence.add("numericValue", JsonNull.INSTANCE);
		assertEquals(10, CalibrationJsonApi.evidenceCommand(evidence).expectedEventVersion());
		assertNull(CalibrationJsonApi.evidenceCommand(evidence).numericValue());
		evidence.addProperty("calibrationSampleId", 91);
		assertEquals(91, CalibrationJsonApi.evidenceCommand(evidence).calibrationSampleId().longValue());

		var process = new JsonObject();
		process.addProperty("calibrationEventId", 42);
		process.addProperty("calibrationStageId", 7);
		process.addProperty("calibrationProcessId", 17);
		process.addProperty("expectedEventVersion", 11);
		assertEquals(17, CalibrationJsonApi.startProcessCommand(process).calibrationProcessId());
		assertEquals(11, CalibrationJsonApi.startProcessCommand(process).expectedEventVersion());
		process.addProperty("reason", "operator attempted bypass");
		assertEquals("operator attempted bypass", CalibrationJsonApi.completeProcessCommand(process).reason());
		var confirmStopped = CalibrationJsonApi.confirmDischargeStoppedCommand(process);
		assertEquals(42, confirmStopped.calibrationEventId());
		assertEquals(7, confirmStopped.calibrationStageId());
		assertEquals(17, confirmStopped.calibrationProcessId());
		assertEquals(11, confirmStopped.expectedEventVersion());

		var safetyFloor = new JsonObject();
		safetyFloor.addProperty("calibrationEventId", 42);
		safetyFloor.addProperty("calibrationStageId", 7);
		safetyFloor.addProperty("expectedEventVersion", 12);
		safetyFloor.addProperty("safetyFloorVoltagePerCell", 1.75);
		var safetyFloorCommand = CalibrationJsonApi.configureDischargeSafetyFloorCommand(safetyFloor);
		assertEquals(42, safetyFloorCommand.calibrationEventId());
		assertEquals(7, safetyFloorCommand.calibrationStageId());
		assertEquals(12, safetyFloorCommand.expectedEventVersion());
		assertEquals(1.75, safetyFloorCommand.safetyFloorVoltagePerCell(), 0.000001);
		safetyFloor.addProperty("safetyFloorVoltagePerCell", 0);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationJsonApi.configureDischargeSafetyFloorCommand(safetyFloor));

		var dischargeMonitor = new JsonObject();
		dischargeMonitor.addProperty("calibrationEventId", 42);
		dischargeMonitor.addProperty("calibrationStageId", 7);
		dischargeMonitor.addProperty("expectedEventVersion", 12);
		dischargeMonitor.addProperty("safetyFloorVoltagePerCell", 1.70);
		dischargeMonitor.addProperty("maxWrongCurrentDirectionSamples", 3);
		var dischargeMonitorCommand = CalibrationJsonApi.configureDischargeMonitorCommand(dischargeMonitor);
		assertEquals(1.70, dischargeMonitorCommand.safetyFloorVoltagePerCell(), 0.000001);
		assertEquals(3, dischargeMonitorCommand.maxWrongCurrentDirectionSamples());
		dischargeMonitor.addProperty("maxWrongCurrentDirectionSamples", 0);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationJsonApi.configureDischargeMonitorCommand(dischargeMonitor));

		var timeScale = new JsonObject();
		timeScale.addProperty("calibrationEventId", 42);
		timeScale.addProperty("calibrationStageId", 7);
		timeScale.addProperty("expectedEventVersion", 12);
		timeScale.addProperty("demoTimeScaleFactor", 60);
		assertEquals(60, CalibrationJsonApi.configureDemoTimeScaleCommand(timeScale).demoTimeScaleFactor());
		timeScale.addProperty("demoTimeScaleFactor", 1.5);
		assertThrows(IllegalArgumentException.class, () -> CalibrationJsonApi.configureDemoTimeScaleCommand(timeScale));

		var floatPolicy = new JsonObject();
		floatPolicy.addProperty("calibrationEventId", 42);
		floatPolicy.addProperty("calibrationStageId", 7);
		floatPolicy.addProperty("expectedEventVersion", 12);
		floatPolicy.addProperty("floatStableDurationMinutes", 30);
		floatPolicy.addProperty("floatVoltageToleranceV", 0.5);
		floatPolicy.addProperty("floatCurrentMinA", 0.0);
		floatPolicy.addProperty("floatCurrentMaxA", 0.1);
		var parsedFloatPolicy = CalibrationJsonApi.configureFloatStablePolicyCommand(floatPolicy);
		assertEquals(30, parsedFloatPolicy.floatStableDurationMinutes());
		assertEquals(0.1, parsedFloatPolicy.floatCurrentMaxA(), 0.000001);
		floatPolicy.addProperty("floatCurrentMaxA", -0.1);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationJsonApi.configureFloatStablePolicyCommand(floatPolicy));

		var activeFloat = new JsonObject();
		activeFloat.addProperty("calibrationEventId", 42);
		activeFloat.addProperty("calibrationStageId", 7);
		activeFloat.addProperty("calibrationProcessId", 9);
		activeFloat.addProperty("processAttemptNumber", 2);
		activeFloat.addProperty("expectedEventVersion", 12);
		activeFloat.addProperty("floatCurrentMinA", 0.0);
		activeFloat.addProperty("floatCurrentMaxA", 0.15);
		activeFloat.addProperty("reason", "Observed stable current");
		assertEquals(2, CalibrationJsonApi.activeFloatCurrentRangeCommand(activeFloat).processAttemptNumber());
		activeFloat.addProperty("reason", " ");
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationJsonApi.activeFloatCurrentRangeCommand(activeFloat));
		activeFloat.addProperty("reason", "Observed stable current");

		var activeVoc = activeFloat.deepCopy();
		activeVoc.remove("floatCurrentMinA"); activeVoc.remove("floatCurrentMaxA");
		activeVoc.addProperty("vocCurrentDeadbandA", 0.05);
		assertEquals(0.05, CalibrationJsonApi.activeVocDeadbandCommand(activeVoc).vocCurrentDeadbandA(), 0.000001);
	}

	@Test
	public void detailSerializesAllowedActionsAcceptedAuditAndNullsExactly() {
		var detail = detail(configuration());
		var json = CalibrationJsonApi.detail(detail);
		assertTrue(json.get("currentStageId").isJsonNull());
		assertTrue(json.get("referenceCalibrationEventId").isJsonNull());
		assertEquals("Session Operator", json.get("createdByName").getAsString());
		assertTrue(json.get("baselineApproved").getAsBoolean());
		assertEquals("Session Operator", json.get("baselineApprovedByName").getAsString());
		var configuration = json.getAsJsonObject("configuration");
		assertTrue(configuration.get("targetDodPercent").isJsonNull());
		assertFalse(configuration.has("kcTemperatureMethod"));
		assertFalse(configuration.has("kcTableVersion"));
		assertFalse(configuration.has("referenceTemperatureC"));
		var stage = json.getAsJsonArray("stages").get(0).getAsJsonObject();
		assertEquals("COMPLETE_STAGE", stage.get("acceptedAction").getAsString());
		assertEquals("Session Operator", stage.get("acceptedActor").getAsString());
		assertTrue(stage.get("blockerCode").isJsonNull());
		var properties = stage.getAsJsonObject("properties");
		assertEquals(55, properties.get("snapshotSequence").getAsLong());
		assertEquals(118.25, properties.get("midpointVoltageV").getAsDouble(), 0.000001);
		assertEquals("UNAVAILABLE", properties.get("comparisonResult").getAsString());
		var action = json.getAsJsonArray("allowedActions").get(0).getAsJsonObject();
		assertEquals("COMPLETE_WITH_GAPS", action.get("actionCode").getAsString());
		assertFalse(action.get("enabled").getAsBoolean());
		assertEquals("CURRENT_STALE", action.get("disabledReason").getAsString());
		assertTrue(action.get("requiresAcknowledgement").getAsBoolean());
	}

	@Test
	public void processDetailSeparatesConfigurationCommonRuntimeAndDischargeRuntime() {
		var base = detail(configuration());
		var processConfiguration = new JsonObject();
		processConfiguration.addProperty("safetyFloorVoltagePerCell", 1.75);
		var runtime = new JsonObject();
		runtime.addProperty("snapshotSequence", 412);
		runtime.add("blockers", new JsonArray());
		var dischargeRuntime = new JsonObject();
		dischargeRuntime.addProperty("rawUsedAh", 18.75);
		dischargeRuntime.addProperty("crossedCellCount", 109);
		dischargeRuntime.addProperty("configuredCellCount", 110);
		dischargeRuntime.addProperty("stringSafeUsableCapacityAh", 176.5);
		dischargeRuntime.addProperty("allCellsEndpointAh", 181.25);
		var legacy = processConfiguration.deepCopy();
		legacy.addProperty("snapshotSequence", 412);
		var process = new Process(17, 42, 7, 0, "CONTROLLED_DISCHARGE", 2, ProcessState.ACTIVE,
				"CONTINUOUS", processConfiguration, runtime, dischargeRuntime, legacy,
				Instant.parse("2026-08-13T10:00:00Z"), null);
		// The deprecated compatibility tree must never alias an authoritative tree.
		process.properties().getAsJsonObject().addProperty("safetyFloorVoltagePerCell", 1.70);
		assertEquals(1.75, process.configuration().getAsJsonObject().get("safetyFloorVoltagePerCell").getAsDouble(), 0.0);
		var withProcess = new EventDetail(base.event(), base.configuration(), base.referenceCalibrationEventId(),
				base.blockers(), base.missingEvidence(), base.stages(), List.of(process), base.allowedActions());

		var json = CalibrationJsonApi.detail(withProcess).getAsJsonArray("processes").get(0).getAsJsonObject();
		assertEquals(1.75, json.getAsJsonObject("configuration").get("safetyFloorVoltagePerCell").getAsDouble(), 0.0);
		assertEquals(412, json.getAsJsonObject("runtime").get("snapshotSequence").getAsLong());
		assertEquals(18.75, json.getAsJsonObject("dischargeRuntime").get("rawUsedAh").getAsDouble(), 0.0);
		assertEquals(109, json.getAsJsonObject("dischargeRuntime").get("crossedCellCount").getAsInt());
		assertEquals(110, json.getAsJsonObject("dischargeRuntime").get("configuredCellCount").getAsInt());
		assertEquals(176.5,
				json.getAsJsonObject("dischargeRuntime").get("stringSafeUsableCapacityAh").getAsDouble(), 0.0);
		assertEquals(181.25, json.getAsJsonObject("dischargeRuntime").get("allCellsEndpointAh").getAsDouble(), 0.0);
		assertEquals(412, json.getAsJsonObject("properties").get("snapshotSequence").getAsLong());
	}

	@Test
	public void processDetailUsesJsonNullForAbsentRuntimeExtensions() {
		var base = detail(configuration());
		var process = new Process(17, 42, 7, 0, "BOOST_CHARGE_TO_VBOOST", 0, ProcessState.NOT_STARTED,
				"FINAL_SNAPSHOT", new JsonObject(), null, null);
		var withProcess = new EventDetail(base.event(), base.configuration(), base.referenceCalibrationEventId(),
				base.blockers(), base.missingEvidence(), base.stages(), List.of(process), base.allowedActions());

		var json = CalibrationJsonApi.detail(withProcess).getAsJsonArray("processes").get(0).getAsJsonObject();
		assertTrue(json.get("runtime").isJsonNull());
		assertTrue(json.get("dischargeRuntime").isJsonNull());
		assertTrue(json.get("configuration").isJsonObject());
	}

	@Test
	public void sampleSerializesCurrentGenerationProvenanceCellsAndNullsExactly() {
		var generation = UUID.fromString("17f1a20e-dbf1-45aa-98d6-d0deac6603be");
		var sample = new Sample(11, 42, 7, 17L, 2, generation, 412, Instant.parse("2026-08-13T10:00:00Z"),
				"CUTOFF", "VALID", null, -19.8, 19.8, "NEGATIVE", 2L,
				Instant.parse("2026-08-13T09:59:59Z"), "GOOD", 251.3, 125.65, 2.285, 27.0, 26.5, 300.0,
				1.65, 198.4, 23.0, 1.014, 201.2, null, null, 99.2, 251.35, 266.75,
				Instant.parse("2026-08-13T10:00:01Z"),
				List.of(new CellSample(73, 1.849, 26.0, 1450, 0.8, "VOLTAGE_ESTIMATE", 91.8,
						"RESISTANCE_ESTIMATE", "VALID", null)));
		var json = CalibrationJsonApi.sample(sample);
		assertEquals(generation.toString(), json.get("snapshotGeneration").getAsString());
		assertEquals(17, json.get("calibrationProcessId").getAsLong());
		assertEquals(2, json.get("processAttemptNumber").getAsInt());
		assertEquals(412, json.get("snapshotSequence").getAsLong());
		assertEquals(-19.8, json.get("rawCurrentA").getAsDouble());
		assertEquals(19.8, json.get("domainCurrentA").getAsDouble());
		assertEquals("NEGATIVE", json.get("currentDirection").getAsString());
		assertEquals("2026-08-13T09:59:59Z", json.get("currentMeasuredAt").getAsString());
		assertEquals("GOOD", json.get("currentQuality").getAsString());
		assertEquals(125.65, json.get("midpointVoltageV").getAsDouble(), 0.000001);
		assertEquals(23.0, json.get("representativeDischargeTemperatureC").getAsDouble());
		assertTrue(json.get("socPct").isJsonNull());
		assertEquals(73, json.getAsJsonArray("cells").get(0).getAsJsonObject().get("cellIndex").getAsInt());
		assertTrue(json.getAsJsonArray("cells").get(0).getAsJsonObject().get("availabilityWarning").isJsonNull());
	}

	private static CalibrationEventConfiguration configuration() {
		return new CalibrationEventConfiguration(CalibrationEventType.INITIAL_BASELINE, "str4", 110, 200, 200,
				null, 1.85, 2.30, 2.45, DischargeCurrentDirection.NEGATIVE, 2, -3.3, -5.0, 0.2, 300,
				60, DischargeMethod.C10, DischargeEndpointMode.FULL_CUTOFF, null, 0.100, 0.200, 0.300,
				"SITE_POLICY_V1");
	}

	private static EventDetail detail(CalibrationEventConfiguration configuration) {
		var event = new EventSummary(42, STRING_ID, CalibrationEventType.INITIAL_BASELINE, EventStatus.COMPLETED,
				null, 9, 12, ACTOR_ID, "Session Operator", Instant.parse("2026-08-13T00:00:00Z"),
				Instant.parse("2026-08-13T12:00:00Z"), Instant.parse("2026-08-13T12:00:00Z"), ACTOR_ID,
				"Session Operator");
		var properties = new JsonObject();
		properties.addProperty("snapshotSequence", 55);
		properties.addProperty("midpointVoltageV", 118.25);
		properties.addProperty("comparisonResult", "UNAVAILABLE");
		var stage = new Stage(7, 42, 0, CalibrationStageType.PREREQUISITES, StageState.COMPLETED,
				properties, "PASS", null, new JsonObject(), Instant.parse("2026-08-13T00:01:00Z"),
				Instant.parse("2026-08-13T00:02:00Z"), ACTOR_ID, "COMPLETE_STAGE", null, "Session Operator",
				Instant.parse("2026-08-13T00:02:00Z"), List.of());
		var action = new AllowedAction(ActionCode.COMPLETE_WITH_GAPS, "Complete with gaps", ActionIntent.WARNING,
				false, "CURRENT_STALE", false, true, List.of("MIDPOINT_VOLTAGE"));
		return new EventDetail(event, configuration, null, List.of(), List.of("MIDPOINT_VOLTAGE"),
				List.of(stage), List.of(), List.of(action));
	}

	private static JsonObject createParams() {
		var params = token("valid-token");
		params.addProperty("stringIndex", 4);
		params.addProperty("eventType", "INITIAL_BASELINE");
		params.addProperty("currentDirection", "NEGATIVE");
		params.addProperty("dischargeMethod", "C10");
		params.addProperty("dischargeEndpointMode", "FULL_CUTOFF");
		return params;
	}

	private static JsonObject invoke(CalibrationJsonApi api, String method, JsonObject params) throws Exception {
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);
		var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest(method, params));
		builder.handle(call);
		return call.getResponse().toJsonObject().getAsJsonObject("result");
	}

	private static void assertRouteFailure(CalibrationJsonApi api, JsonObject params, String expected) throws Exception {
		var method = CalibrationJsonApi.class.getDeclaredMethod("create", JsonObject.class,
				AuthenticatedPrincipal.class);
		method.setAccessible(true);
		var exception = assertThrows(InvocationTargetException.class,
				() -> method.invoke(api, params, principal(PlatformRole.BMS_OPERATOR)));
		assertTrue(exception.getCause() instanceof IllegalArgumentException);
		assertTrue(exception.getCause().getMessage().contains(expected));
	}

	private static CalibrationJsonApi api(CaptureService service, Set<PlatformRole> roles) throws Exception {
		var principal = roles == null ? null
				: new AuthenticatedPrincipal(ACTOR_ID, "session-operator", "Session Operator", roles);
		service.sessionPrincipal = principal;
		var api = new CalibrationJsonApi();
		setField(api, "authentication", new Authentication(principal));
		setField(api, "service", service.proxy());
		return api;
	}

	private static AuthenticatedPrincipal principal(PlatformRole... roles) {
		return new AuthenticatedPrincipal(ACTOR_ID, "principal", "Principal Name", Set.of(roles));
	}





	private static AuthenticatedPrincipal invokeAuthenticate(CalibrationJsonApi api, JsonObject params)
			throws Exception {
		var method = CalibrationJsonApi.class.getDeclaredMethod("authenticate", JsonObject.class);
		method.setAccessible(true);
		return (AuthenticatedPrincipal) method.invoke(api, params);
	}

	private static JsonObject token(String value) {
		var params = new JsonObject();
		params.addProperty("token", value);
		return params;
	}

	private static JsonObject tokenNumber(int value) {
		var params = new JsonObject();
		params.addProperty("token", value);
		return params;
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				var field = type.getDeclaredField(name);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static final class CaptureService {
		private Object[] createArgs;
		CalibrationEventOperations proxy() {
			return (CalibrationEventOperations) Proxy.newProxyInstance(CalibrationEventOperations.class.getClassLoader(),
					new Class<?>[] { CalibrationEventOperations.class }, (proxy, method, args) -> {
				return switch (method.getName()) {
				case "create" -> {
					this.createArgs = args;
					yield args[1] == CalibrationEventType.MONTHLY_INSPECTION ? monthlyDetail(false) : detail(configuration());
				}
				case "start" -> this.start((long) args[0], (long) args[1], (AuthenticatedPrincipal) args[2]);
				case "deleteDraft" -> this.deleteDraft((DeleteDraftCommand) args[0], (AuthenticatedPrincipal) args[1]);
				case "curvePoints" -> this.curvePoints((long) args[0], (Long) args[1], (Integer) args[2],
						(int) args[3], (int) args[4], (AuthenticatedPrincipal) args[5]);
				default -> throw new UnsupportedOperationException(method.getName());
				};
			});
		}

		private AuthenticatedPrincipal sessionPrincipal;
		private long startedWithVersion = -1;
		private DeleteDraftCommand deletedCommand;
		private long curveEventId;
		private Long curveVersionId;
		private Integer curveCellIndex;
		private int curveOffset;
		private int curveLimit;



		public EventDetail start(long eventId, long expectedEventVersion, AuthenticatedPrincipal principal) {
			this.startedWithVersion = expectedEventVersion;
			return monthlyDetail(true);
		}

		public DeletedEvent deleteDraft(DeleteDraftCommand command, AuthenticatedPrincipal principal) {
			this.deletedCommand = command;
			return new DeletedEvent(command.calibrationEventId());
		}

		public CurvePointPage curvePoints(long eventId, Long curveVersionId, Integer cellIndex, int offset, int limit,
				AuthenticatedPrincipal principal) {
			this.curveEventId = eventId;
			this.curveVersionId = curveVersionId;
			this.curveCellIndex = cellIndex;
			this.curveOffset = offset;
			this.curveLimit = limit;
			var metadata = new ReferenceCurve(91, eventId, STRING_ID, 7, 17, 0, 1, 200, 180, 184,
					110, 1, "abc", Instant.parse("2026-08-27T00:00:00Z"), true);
			var point = new CurvePoint(91, 7, 0, 501, 50, 55.5556, 100, 2.04, 25.1, 1800,
					null, null, 224.4, 2.04, "VALID", true);
			return new CurvePointPage(metadata, offset, limit, 1, List.of(point));
		}
	}



	private static EventDetail monthlyDetail(boolean active) {
		var configuration = new CalibrationEventConfiguration(CalibrationEventType.MONTHLY_INSPECTION, "str4", 110,
				200, 200, null, 1.85, 2.30, 2.45, DischargeCurrentDirection.NEGATIVE, 1, -3.3, -5.0,
				0.2, 300, 60, null, null, null, 0.100, 0.200, 0.300, "SITE_POLICY_V1");
		var event = new EventSummary(42, STRING_ID, CalibrationEventType.MONTHLY_INSPECTION,
				active ? EventStatus.ACTIVE : EventStatus.DRAFT, active ? 101L : null, active ? 1 : 0, 0,
				ACTOR_ID, "Session Operator", Instant.parse("2026-08-14T00:00:00Z"), null, null, null, null);
		if (!active) return new EventDetail(event, configuration, null, List.of(), List.of(), List.of(), List.of(), List.of());
		var types = List.of(CalibrationStageType.PREREQUISITES, CalibrationStageType.MONTHLY_INSPECTION, CalibrationStageType.COMPLETION);
		var stages = java.util.stream.IntStream.range(0, types.size())
				.mapToObj(index -> new Stage(101 + index, 42, index, types.get(index),
						index == 0 ? StageState.ACTIVE : StageState.NOT_STARTED, new JsonObject(), null, null,
						new JsonObject(), index == 0 ? Instant.parse("2026-08-14T00:01:00Z") : null, null, null,
						null, null, null, null, List.of()))
				.toList();
		return new EventDetail(event, configuration, null, List.of(), List.of(), stages, List.of(), List.of());
	}



	private static final class Authentication implements PlatformAuthenticationService {
		private final AuthenticatedPrincipal principal;

		private Authentication(AuthenticatedPrincipal principal) {
			this.principal = principal;
		}

		@Override
		public Optional<Login> authenticate(String username, char[] password) {
			return Optional.empty();
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			return "valid-token".equals(token) ? Optional.ofNullable(this.principal) : Optional.empty();
		}

		@Override
		public boolean logout(String token) {
			return false;
		}

		@Override
		public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) {
			return false;
		}

		@Override
		public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) {
			return Optional.empty();
		}
	}
}
