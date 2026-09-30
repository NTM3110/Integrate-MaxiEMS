package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationStoreTestAccess;

import io.openems.edge.bms.service.calibration.implement.CalibrationConflictException;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionCode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventStatus;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventSummary;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ExecuteActionCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Process;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ProcessState;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Stage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StageState;

public class CalibrationActionPolicyTest {

	@Test
	public void derivesCompletionAndDestructiveActionsWithoutMakingStagesSkippable() {
		var actions = CalibrationActionPolicy.derive(detail(EventStatus.ACTIVE, StageState.ACTIVE, null, List.of(),
				List.of()));
		assertTrue(find(actions, ActionCode.COMPLETE_STAGE).enabled());
		assertFalse(actions.stream().anyMatch(action -> action.code() == ActionCode.SKIP_STAGE));
		assertTrue(find(actions, ActionCode.FAIL_STAGE).requiresReason());
		assertTrue(find(actions, ActionCode.ABORT_EVENT).requiresAcknowledgement());
	}

	@Test
	public void blocksNormalCompletionAndRequiresExactGapAcknowledgement() {
		var gap = CalibrationActionPolicy.derive(detail(CalibrationStageType.BASELINE_CAPTURE, EventStatus.ACTIVE,
				StageState.ACTIVE, null, List.of(), List.of("VOC_BASELINE_CONFIRMATION")));
		assertFalse(find(gap, ActionCode.COMPLETE_STAGE).enabled());
		assertFalse(find(gap, ActionCode.COMPLETE_WITH_GAPS).enabled());

		var blocked = CalibrationActionPolicy.derive(detail(CalibrationStageType.BASELINE_CAPTURE, EventStatus.BLOCKED,
				StageState.BLOCKED, "CURRENT_STALE", List.of("CURRENT_STALE"), List.of("VOC_BASELINE_CONFIRMATION")));
		assertFalse(find(blocked, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(blocked, ActionCode.COMPLETE_STAGE).disabledReason().contains("CURRENT_STALE"));
	}

	@Test
	public void unrelatedEventGapsDoNotBlockTheCurrentStage() {
		var actions = CalibrationActionPolicy.derive(detail(EventStatus.ACTIVE, StageState.ACTIVE, null, List.of(),
				List.of("RIPPLE_BEFORE_LOAD")));
		assertTrue(find(actions, ActionCode.COMPLETE_STAGE).enabled());
		assertFalse(actions.stream().anyMatch(action -> action.code() == ActionCode.COMPLETE_WITH_GAPS));
	}

	@Test
	public void monthlyComputedMidpointIsNotAManualEvidenceGap() {
		var actions = CalibrationActionPolicy.derive(detail(EventStatus.ACTIVE, StageState.ACTIVE, null, List.of(),
				List.of("MIDPOINT_VOLTAGE")));
		assertTrue(find(actions, ActionCode.COMPLETE_STAGE).enabled());
		assertFalse(actions.stream().anyMatch(action -> action.code() == ActionCode.COMPLETE_WITH_GAPS));
	}

	@Test
	public void manualEvidencePolicyRejectsUnknownAndWrongStageTypes() {
		var valid = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "HVAC_INSPECTION", "PASS",
				null, null, null, null, null, null, null, null, null);
		CalibrationManualEvidencePolicy.validate(CalibrationStageType.MONTHLY_INSPECTION, valid);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationManualEvidencePolicy.validate(CalibrationStageType.DISCHARGE, valid));
		var ripple = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "RIPPLE_AFTER_LOAD", "RECORDED",
				0.08, null, "V", null, null, null, null, null, null);
		CalibrationManualEvidencePolicy.validate(CalibrationStageType.BASELINE_CAPTURE, ripple);
		var setpoints = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
				"CHARGE_SETPOINTS_REVIEWED", "PASS", null, null, null, null, null, null, null, null, null);
		CalibrationManualEvidencePolicy.validate(CalibrationStageType.INITIAL_CHARGE, setpoints);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationManualEvidencePolicy.validate(CalibrationStageType.RECHARGE, setpoints));
		var recoveryMcb = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
				"RECOVERY_CHARGER_MCB_CLOSED_CONFIRMED", "PASS", null, null, null, null, null, null, null,
				null, null);
		CalibrationManualEvidencePolicy.validate(CalibrationStageType.RECHARGE, recoveryMcb);
		var textRipple = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "RIPPLE_AFTER_LOAD", "RECORDED",
				null, "0.08", "V", null, null, null, null, null, null);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationManualEvidencePolicy.validate(CalibrationStageType.BASELINE_CAPTURE, textRipple));
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationManualEvidencePolicy.validate(CalibrationStageType.MONTHLY_INSPECTION, ripple));
		var unknown = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "ARBITRARY", "PASS", null,
				null, null, null, null, null, null, null, null);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationManualEvidencePolicy.validate(CalibrationStageType.MONTHLY_INSPECTION, unknown));
		assertThrows(IllegalArgumentException.class, () -> CalibrationManualEvidencePolicy
				.validate(CalibrationStageType.INITIAL_CHARGE, new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
						"CHARGER_BOOST_CONFIRMATION", "PASS", null, null, null, null, null, null, null, null, null)));
	}

	@Test
	public void waitingForLoadUsesSavedConfigurationAndRequiresOnlyPhysicalLoadEvidence() {
		var properties = new JsonObject();
		var waiting = stage(CalibrationStageType.WAITING_FOR_LOAD, properties);
		var blocked = CalibrationActionPolicy.derive(detailWithProcesses(waiting, List.of()));
		assertFalse(find(blocked, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(blocked, ActionCode.COMPLETE_STAGE).disabledReason().contains("TEST_LOAD_CONNECTED"));

		var evidence = new com.google.gson.JsonArray();
		evidence.add("TEST_LOAD_CONNECTED");
		properties.add("manualEvidenceTypes", evidence);
		var ready = CalibrationActionPolicy.derive(detailWithProcesses(waiting, List.of()));
		assertTrue(find(ready, ActionCode.COMPLETE_STAGE).enabled());

		var loadConnected = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
				"TEST_LOAD_CONNECTED", "PASS", null, "Confirmed", null, null, null, null, null, null, null,
				null);
		CalibrationManualEvidencePolicy.validate(CalibrationStageType.WAITING_FOR_LOAD, loadConnected);
		var obsoleteProfileReview = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
				"DISCHARGE_PROFILE_REVIEWED", "PASS", null, "Confirmed", null, null, null, null, null, null,
				null, null);
		assertThrows(IllegalArgumentException.class, () -> CalibrationManualEvidencePolicy
				.validate(CalibrationStageType.WAITING_FOR_LOAD, obsoleteProfileReview));
	}

	@Test
	public void recoveryFloatReviewUsesCanonicalMeasurementEvidence() {
		var current = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "CHARGER_OUTPUT_CURRENT", "PASS",
				0.082, null, "A", null, null, null, null, null, null, 91L);
		var midpoint = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "MIDPOINT_VOLTAGE", "PASS",
				126.5, null, "V", null, null, null, null, null, null, 91L);
		var ripple = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "RIPPLE_BEFORE_LOAD", "PASS",
				0.6, null, "V", null, null, null, null, null, null);
		var resistance = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
				"CELL_RESISTANCE_SAMPLE_REVIEWED", "PASS", null, "Confirmed", null, null, null, null, null, null,
				null, 91L);
		for (var command : List.of(current, midpoint, ripple, resistance)) {
			CalibrationManualEvidencePolicy.validate(CalibrationStageType.POST_RECHARGE_FLOAT, command);
		}
		assertThrows(IllegalArgumentException.class, () -> CalibrationManualEvidencePolicy.validate(
				CalibrationStageType.POST_RECHARGE_FLOAT, new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
						"CHARGER_OUTPUT_CURRENT", "PASS", 82.0, null, "mA", null, null, null, null, null, null)));
		assertThrows(IllegalArgumentException.class, () -> CalibrationManualEvidencePolicy.validate(
				CalibrationStageType.POST_RECHARGE_FLOAT, new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3,
						"RIPPLE_BEFORE_LOAD", "PASS", -0.1, null, "V", null, null, null, null, null, null)));
	}

	@Test
	public void recoveryFloatCannotCompleteUntilEveryDisplayedReviewIsPersisted() {
		var properties = new JsonObject();
		properties.addProperty("recommendation", "READY_TO_COMPLETE");
		var completedProcess = List.of(process("RECOVERY_FLOAT_STABILIZATION", ProcessState.COMPLETED));
		var evidence = new com.google.gson.JsonArray();
		for (var type : List.of("RECOVERY_FLOAT_STABILITY_REVIEWED", "CELL_RESISTANCE_SAMPLE_REVIEWED",
				"CHARGER_OUTPUT_CURRENT", "MIDPOINT_VOLTAGE")) evidence.add(type);
		properties.add("manualEvidenceTypes", evidence);
		var blocked = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.POST_RECHARGE_FLOAT, properties), completedProcess));
		assertFalse(find(blocked, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(blocked, ActionCode.COMPLETE_STAGE).disabledReason().contains("RIPPLE_BEFORE_LOAD"));
		evidence.add("RIPPLE_BEFORE_LOAD");
		var ready = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.POST_RECHARGE_FLOAT, properties), completedProcess));
		assertTrue(find(ready, ActionCode.COMPLETE_STAGE).enabled());

		properties.addProperty("recommendation", "CONTINUE");
		var staleFloatRecommendation = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.POST_RECHARGE_FLOAT, properties), completedProcess));
		assertFalse(find(staleFloatRecommendation, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(staleFloatRecommendation, ActionCode.COMPLETE_STAGE).disabledReason()
				.contains("Float-current"));
	}

	@Test
	public void cooldownCompletionUsesCompletedProcessAndOperatorEvidenceInsteadOfStaleStageRecommendation() {
		var properties = new JsonObject();
		properties.addProperty("recommendation", "CONTINUE");
		var evidence = new com.google.gson.JsonArray();
		for (var type : List.of("CHARGER_MCB_OPEN_FOR_COOLDOWN", "AMBIENT_TEMPERATURE_REVIEWED")) evidence.add(type);
		properties.add("manualEvidenceTypes", evidence);
		var missingSafetyReview = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.PRE_DISCHARGE_FLOAT, properties),
				List.of(process("COOL_DOWN_TO_AMBIENT", ProcessState.COMPLETED))));
		assertFalse(find(missingSafetyReview, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(missingSafetyReview, ActionCode.COMPLETE_STAGE).disabledReason()
				.contains("COOLDOWN_SAFETY_VALVES_REVIEWED"));
		evidence.add("COOLDOWN_SAFETY_VALVES_REVIEWED");

		var activeProcess = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.PRE_DISCHARGE_FLOAT, properties),
				List.of(process("COOL_DOWN_TO_AMBIENT", ProcessState.ACTIVE))));
		assertFalse(find(activeProcess, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(activeProcess, ActionCode.COMPLETE_STAGE).disabledReason()
				.contains("physical processes"));

		var completedProcess = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.PRE_DISCHARGE_FLOAT, properties),
				List.of(process("COOL_DOWN_TO_AMBIENT", ProcessState.COMPLETED))));
		assertTrue(find(completedProcess, ActionCode.COMPLETE_STAGE).enabled());
	}

	@Test
	public void initialChargeNeedsBothAutomaticProcessesAndTheirOperatorReviewsBeforeCompletion() {
		var properties = new JsonObject();
		properties.addProperty("recommendation", "READY_TO_COMPLETE");
		var evidence = new com.google.gson.JsonArray();
		evidence.add("CHARGE_SETPOINTS_REVIEWED");
		properties.add("manualEvidenceTypes", evidence);
		var completedProcesses = List.of(process("BOOST_CHARGE_TO_VBOOST", ProcessState.COMPLETED),
				process("FLOAT_SETTLEMENT_TO_VFLOAT", ProcessState.COMPLETED));

		var missingBoostReview = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.INITIAL_CHARGE, properties), completedProcesses));
		assertFalse(find(missingBoostReview, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(missingBoostReview, ActionCode.COMPLETE_STAGE).disabledReason()
				.contains("BOOST_TARGET_REVIEWED"));
		evidence.add("BOOST_TARGET_REVIEWED");
		var missingFloatReview = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.INITIAL_CHARGE, properties), completedProcesses));
		assertFalse(find(missingFloatReview, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(missingFloatReview, ActionCode.COMPLETE_STAGE).disabledReason()
				.contains("FLOAT_MODE_REVIEWED"));
		evidence.add("FLOAT_MODE_REVIEWED");
		var ready = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.INITIAL_CHARGE, properties), completedProcesses));
		assertTrue(find(ready, ActionCode.COMPLETE_STAGE).enabled());
	}

	@Test
	public void revalidationOffersOnlyRestoreOrAbort() {
		var actions = CalibrationActionPolicy.derive(detail(EventStatus.REVALIDATION_REQUIRED,
				StageState.REVALIDATION_REQUIRED, null, List.of(), List.of()));
		assertEquals(List.of(ActionCode.REVALIDATE_STAGE, ActionCode.ABORT_EVENT),
				actions.stream().map(CalibrationEventModels.AllowedAction::code).toList());
		assertTrue(find(actions, ActionCode.REVALIDATE_STAGE).requiresAcknowledgement());
	}

	@Test
	public void failedCurrentStageCannotReturnToSuccessfulCompletion() {
		var actions = CalibrationActionPolicy.derive(detail(EventStatus.BLOCKED, StageState.FAILED, null, List.of(),
				List.of()));
		assertEquals(List.of(ActionCode.ABORT_EVENT),
				actions.stream().map(CalibrationEventModels.AllowedAction::code).toList());
	}

	@Test
	public void confirmedSafetyStopOffersOnlyDedicatedRedoOrAbortPath() {
		var actions = CalibrationActionPolicy.derive(detail(CalibrationStageType.DISCHARGE, EventStatus.BLOCKED,
				StageState.BLOCKED, "SAFETY_FLOOR_REACHED", List.of("SAFETY_FLOOR_REACHED"), List.of()));
		assertEquals(List.of(ActionCode.ABORT_EVENT),
				actions.stream().map(CalibrationEventModels.AllowedAction::code).toList());
	}

	@Test
	public void validatesVersionReasonAndGapAcknowledgement() {
		var detail = detail(CalibrationStageType.SIX_MONTH_INSPECTION, EventStatus.ACTIVE, StageState.ACTIVE, null,
				List.of(), List.of("LINKED_PERIODIC_CAPACITY_RECORD"));
		assertThrows(CalibrationConflictException.class,
				() -> CalibrationCommandValidator.requireVersion(detail, 2));
		CalibrationCommandValidator.requireVersion(detail, 3);

		var actions = CalibrationActionPolicy.derive(detail);
		var gaps = find(actions, ActionCode.COMPLETE_WITH_GAPS);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationCommandValidator.validatePayload(gaps,
						new ExecuteActionCommand(42, 7, ActionCode.COMPLETE_WITH_GAPS, 3, null, List.of())));
		CalibrationCommandValidator.validatePayload(gaps,
				new ExecuteActionCommand(42, 7, ActionCode.COMPLETE_WITH_GAPS, 3, null,
						List.of("LINKED_PERIODIC_CAPACITY_RECORD")));

		var fail = find(actions, ActionCode.FAIL_STAGE);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationCommandValidator.validatePayload(fail,
						new ExecuteActionCommand(42, 7, ActionCode.FAIL_STAGE, 3, " ",
								List.of("FAIL_BLOCKS_EVENT"))));
		CalibrationCommandValidator.validatePayload(fail,
				new ExecuteActionCommand(42, 7, ActionCode.FAIL_STAGE, 3, "sensor conflict",
						List.of("FAIL_BLOCKS_EVENT")));
	}

	@Test
	public void serviceAuthorizationExplicitlyAllowsOperatorReads() throws Exception {
		var viewer = new AuthenticatedPrincipal(UUID.randomUUID(), "viewer", "Viewer",
				java.util.Set.of(PlatformRole.BMS_VIEWER));
		var operator = new AuthenticatedPrincipal(UUID.randomUUID(), "operator", "Operator",
				java.util.Set.of(PlatformRole.BMS_OPERATOR));
		var none = new AuthenticatedPrincipal(UUID.randomUUID(), "none", "No Role", java.util.Set.of());
		assertTrue(viewer.hasRole(PlatformRole.BMS_VIEWER));
		assertFalse(operator.hasRole(PlatformRole.BMS_VIEWER)); // service adds this hierarchy explicitly
		assertThrows(SecurityException.class, () -> invokeViewerGuard(none));
		invokeViewerGuard(operator);
	}

	@Test
	public void boostStartRequiresStageEvidenceAndAutomaticProcessesRejectManualCompletion() {
		var properties = new JsonObject();
		var initialCharge = stage(CalibrationStageType.INITIAL_CHARGE, properties);
		var boost = process("BOOST_CHARGE_TO_VBOOST", 0, "FINAL_SNAPSHOT");
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationStoreTestAccess.validateProcessStartPolicy(initialCharge, boost));
		var evidence = new com.google.gson.JsonArray();
		evidence.add("CHARGE_SETPOINTS_REVIEWED");
		properties.add("manualEvidenceTypes", evidence);
		CalibrationStoreTestAccess.validateProcessStartPolicy(initialCharge, boost);

		var floatProcess = process("FLOAT_SETTLEMENT_TO_VFLOAT", 1, "FINAL_SNAPSHOT");
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationStoreTestAccess.validateProcessStartPolicy(initialCharge, floatProcess));
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationStoreTestAccess.validateManualProcessCompletion(boost));
		CalibrationStoreTestAccess.validateManualProcessCompletion(process("CONTROLLED_DISCHARGE", 0, "CONTINUOUS"));
	}

	@Test
	public void reviewEvidenceRequiresItsAutomaticProcessToComplete() {
		var stage = stage(CalibrationStageType.INITIAL_CHARGE, new JsonObject());
		var command = new CalibrationEventModels.ManualEvidenceCommand(42, 7, 3, "BOOST_TARGET_REVIEWED", "PASS",
				null, null, null, null, null, null, null, null, null);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationStoreTestAccess.validateInitialBaselineReviewEvidence(detailWithProcesses(stage,
						List.of(process("BOOST_CHARGE_TO_VBOOST", ProcessState.ACTIVE))), stage, command));
		CalibrationStoreTestAccess.validateInitialBaselineReviewEvidence(detailWithProcesses(stage,
				List.of(process("BOOST_CHARGE_TO_VBOOST", ProcessState.COMPLETED))), stage, command);
	}

	@Test
	public void completionRequiresEveryDetailedPrerequisiteEvidence() {
		var properties = new JsonObject();
		var evidence = new com.google.gson.JsonArray();
		for (var type : List.of("BATTERY_CHARGER_CONNECTIONS_CHECKED", "LOAD_MCB_OPEN_CONFIRMED",
				"SAFETY_VISUAL_INSPECTION_CONFIRMED")) evidence.add(type);
		properties.add("manualEvidenceTypes", evidence);
		properties.addProperty("recommendation", "READY_TO_COMPLETE");
		var actions = CalibrationActionPolicy.derive(detailWithProcesses(
				stage(CalibrationStageType.PREREQUISITES, properties), List.of()));
		assertFalse(find(actions, ActionCode.COMPLETE_STAGE).enabled());
		assertTrue(find(actions, ActionCode.COMPLETE_STAGE).disabledReason()
				.contains("OPEN_CIRCUIT_VOLTAGE_REVIEWED"));
	}

	@Test
	public void activeLegacyAggregateWithoutProcessesFailsWithRecoveryGuidance() {
		var incompatible = detail(CalibrationStageType.INITIAL_CHARGE, EventStatus.ACTIVE, StageState.ACTIVE,
				null, List.of(), List.of());
		var error = assertThrows(IllegalArgumentException.class,
				() -> CalibrationStoreTestAccess.validateActiveProcessDefinitions(incompatible));
		assertTrue(error.getMessage().contains("abort"));
		assertTrue(error.getMessage().contains("create a new Initial Baseline"));

		var configuration = CalibrationTestFixtures.initial(
				io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection.POSITIVE);
		var missingTarget = process("BOOST_CHARGE_TO_VBOOST", 0, "FINAL_SNAPSHOT");
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationStoreTestAccess.validateRequiredProcessProperties(configuration, missingTarget));
		var processProperties = new JsonObject();
		processProperties.addProperty("boostTargetVoltageV",
				configuration.boostVoltagePerCell() * configuration.cellCount());
		processProperties.addProperty("floatVoltageToleranceV", configuration.floatVoltageToleranceV());
		processProperties.addProperty("floatCurrentMinA", configuration.floatCurrentMinA());
		processProperties.addProperty("floatCurrentMaxA", configuration.floatCurrentMaxA());
		processProperties.addProperty("floatStableDurationMinutes", configuration.floatStableDurationMinutes());
		processProperties.addProperty("chargeCurrentLimitA", configuration.chargeCurrentLimitA());
		CalibrationStoreTestAccess.validateRequiredProcessProperties(configuration,
				process("BOOST_CHARGE_TO_VBOOST", 0, "FINAL_SNAPSHOT", processProperties));
	}

	@Test
	public void legacyConfigurationReadbackInjectsFloatPolicyDefaults() {
		var json = new com.google.gson.Gson().toJsonTree(
				CalibrationTestFixtures.initial(io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection.POSITIVE))
				.getAsJsonObject();
		for (var field : List.of("floatStableDurationMinutes", "floatVoltageToleranceV", "floatCurrentMinA",
				"floatCurrentMaxA", "chargeCurrentLimitA")) json.remove(field);
		var configuration = CalibrationStoreTestAccess.configuration(json.toString());
		assertEquals(CalibrationEventConfiguration.DEFAULT_FLOAT_STABLE_DURATION_MINUTES,
				configuration.floatStableDurationMinutes());
		assertEquals(CalibrationEventConfiguration.DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V,
				configuration.floatVoltageToleranceV(), 0.000001);
		assertEquals(CalibrationEventConfiguration.LEGACY_FLOAT_CURRENT_MIN_A,
				configuration.floatCurrentMinA(), 0.000001);
		assertEquals(CalibrationEventConfiguration.LEGACY_FLOAT_CURRENT_MAX_A,
				configuration.floatCurrentMaxA(), 0.000001);
		assertEquals(CalibrationEventConfiguration.defaultChargeCurrentLimitA(configuration.ratedCapacityAh()),
				configuration.chargeCurrentLimitA(), 0.000001);
	}

	private static Stage stage(CalibrationStageType type, JsonObject properties) {
		return new Stage(7, 42, 1, type, StageState.ACTIVE, properties, null, null, new JsonObject(), Instant.now(),
				null, null, null, null, null, null, List.of());
	}

	private static Process process(String type, int order, String samplingPolicy) {
		return process(type, order, samplingPolicy, new JsonObject());
	}

	private static Process process(String type, int order, String samplingPolicy, JsonObject properties) {
		return new Process(17, 42, 7, order, type, 0, ProcessState.NOT_STARTED, samplingPolicy, properties,
				null, null);
	}

	private static Process process(String type, ProcessState state) {
		return new Process(17, 42, 7, 0, type, 0, state, "FINAL_SNAPSHOT", new JsonObject(), null, null);
	}

	private static EventDetail detailWithProcesses(Stage stage, List<Process> processes) {
		var event = new EventSummary(42, UUID.randomUUID(), CalibrationEventType.INITIAL_BASELINE, EventStatus.ACTIVE,
				stage.id(), 3, 9, UUID.randomUUID(), "Operator Name", Instant.now(), null, null, null, null);
		return new EventDetail(event, CalibrationTestFixtures.initial(
				io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection.POSITIVE), null, List.of(), List.of(),
				List.of(stage), processes, List.of());
	}

	private static void invokeViewerGuard(AuthenticatedPrincipal principal) throws Exception {
		var method = CalibrationEventService.class.getDeclaredMethod("requireViewer", AuthenticatedPrincipal.class);
		method.setAccessible(true);
		try {
			method.invoke(null, principal);
		} catch (java.lang.reflect.InvocationTargetException e) {
			if (e.getCause() instanceof Exception exception) throw exception;
			throw e;
		}
	}

	private static EventDetail detail(EventStatus eventStatus, StageState stageState, String blocker,
			List<String> blockers, List<String> missing) {
		return detail(CalibrationStageType.MONTHLY_INSPECTION, eventStatus, stageState, blocker, blockers, missing);
	}

	private static EventDetail detail(CalibrationStageType stageType, EventStatus eventStatus, StageState stageState,
			String blocker, List<String> blockers, List<String> missing) {
		var eventId = 42L;
		var stageId = 7L;
		var actorId = UUID.randomUUID();
		var eventType = stageType == CalibrationStageType.MONTHLY_INSPECTION
				? CalibrationEventType.MONTHLY_INSPECTION : CalibrationEventType.INITIAL_BASELINE;
		var event = new EventSummary(eventId, UUID.randomUUID(), eventType,
				eventStatus, stageId, 3, 9, actorId, "Operator Name", Instant.now(), null, null, null, null);
		var properties = new JsonObject();
		properties.addProperty("measurementAccepted", true);
		properties.addProperty("recommendation", "READY_TO_COMPLETE");
		var evidence = new com.google.gson.JsonArray();
		if (stageType == CalibrationStageType.MONTHLY_INSPECTION) {
			for (var type : List.of("HVAC_INSPECTION", "CONNECTOR_INSPECTION",
					"CORROSION_INSPECTION", "CASE_INSPECTION", "GROUND_IMBALANCE_INSPECTION")) evidence.add(type);
		}
		properties.add("manualEvidenceTypes", evidence);
		var stage = new Stage(stageId, eventId, 1, stageType, stageState,
				properties, null, blocker, new JsonObject(), Instant.now(), null, null, null, null, null, null,
				List.of());
		return new EventDetail(event, CalibrationTestFixtures.periodic(
				io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection.POSITIVE, 50.0), null,
				blockers, missing, List.of(stage), List.of(), List.of());
	}

	private static CalibrationEventModels.AllowedAction find(List<CalibrationEventModels.AllowedAction> actions,
			ActionCode code) {
		return actions.stream().filter(action -> action.code() == code).findFirst().orElseThrow();
	}
}
