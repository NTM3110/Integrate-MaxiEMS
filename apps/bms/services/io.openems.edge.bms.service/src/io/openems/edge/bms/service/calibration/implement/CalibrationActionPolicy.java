package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionCode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionIntent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventStatus;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StageState;

/** Pure authoritative action derivation shared by service and focused tests. */
final class CalibrationActionPolicy {

	private CalibrationActionPolicy() {
	}

	static List<AllowedAction> derive(EventDetail detail) {
		if (detail.event().currentStageId() == null || detail.event().closedAt() != null) return List.of();
		var stage = detail.stages().stream().filter(value -> value.id() == detail.event().currentStageId()).findFirst()
				.orElseThrow(() -> new IllegalStateException("Current calibration stage is missing"));
		if (stage.state() == StageState.COMPLETED || stage.state() == StageState.SKIPPED
				|| stage.state() == StageState.FAILED) {
			return List.of(action(ActionCode.ABORT_EVENT, "Abort calibration event", ActionIntent.DANGER, true, null,
					true, true, List.of("ABORT_IS_FINAL")));
		}
		if (stage.state() == StageState.BLOCKED && "SAFETY_FLOOR_REACHED".equals(stage.blockerCode())) {
			// Redo is a dedicated versioned command. Once the physical stop has been
			// confirmed, no generic stage mutation may bypass the safety outcome.
			return List.of(action(ActionCode.ABORT_EVENT, "Abort calibration event", ActionIntent.DANGER, true, null,
					true, true, List.of("ABORT_IS_FINAL")));
		}
		if (stage.state() == StageState.REVALIDATION_REQUIRED
				|| detail.event().status() == EventStatus.REVALIDATION_REQUIRED) {
			return List.of(action(ActionCode.REVALIDATE_STAGE, "Revalidate physical stage", ActionIntent.WARNING,
					true, null, false, true, List.of("CURRENT_PHYSICAL_TOPOLOGY")),
					action(ActionCode.ABORT_EVENT, "Abort calibration event", ActionIntent.DANGER, true, null, true,
						true, List.of("ABORT_IS_FINAL")));
		}

		var actions = new ArrayList<AllowedAction>();
		var blockingReason = blockingReason(detail, stage);
		if (blockingReason == null) blockingReason = incompleteProcessReason(detail, stage.id());
		if (blockingReason == null) blockingReason = evidenceBlocker(stage);
		var stageGaps = stageGaps(detail, stage.type());
		actions.add(action(ActionCode.COMPLETE_STAGE, "Complete stage", ActionIntent.PRIMARY,
				blockingReason == null && stageGaps.isEmpty(),
				blockingReason != null ? blockingReason : stageGaps.isEmpty() ? null
						: "Non-blocking evidence gaps must be acknowledged",
				false, false, List.of()));
		if (!stageGaps.isEmpty()) {
			actions.add(action(ActionCode.COMPLETE_WITH_GAPS, "Complete with evidence gaps", ActionIntent.WARNING,
					blockingReason == null, blockingReason, false, true, stageGaps));
		}
		if (isOptional(stage.type())) {
			actions.add(action(ActionCode.SKIP_STAGE, "Skip optional stage", ActionIntent.WARNING, true, null, true,
					false, List.of()));
		}
		actions.add(action(ActionCode.FAIL_STAGE, "Fail stage", ActionIntent.DANGER, true, null, true, true,
				List.of("FAIL_BLOCKS_EVENT")));
		actions.add(action(ActionCode.ABORT_EVENT, "Abort calibration event", ActionIntent.DANGER, true, null, true,
				true, List.of("ABORT_IS_FINAL")));
		return List.copyOf(actions);
	}

	private static String incompleteProcessReason(EventDetail detail, long stageId) {
		var processes = detail.processes().stream().filter(value -> value.calibrationStageId() == stageId).toList();
		if (processes.isEmpty()) return null;
		return processes.stream().allMatch(value -> value.state() == CalibrationEventModels.ProcessState.COMPLETED)
				? null : "All required physical processes must be completed";
	}

	private static List<String> stageGaps(EventDetail detail, CalibrationStageType stageType) {
		var owned = switch (stageType) {
		case BASELINE_CAPTURE -> Set.of("LOAD_CONNECTED_CONFIRMATION", "RIPPLE_AFTER_LOAD",
				"CHARGER_MCB_OPEN_CONFIRMATION", "VOC_BASELINE_CONFIRMATION");
		case MONTHLY_INSPECTION -> Set.of();
		case SIX_MONTH_INSPECTION -> Set.of("RIPPLE_BEFORE_LOAD", "RIPPLE_AFTER_LOAD",
				"LINKED_PERIODIC_CAPACITY_RECORD");
		default -> Set.<String>of();
		};
		return detail.missingEvidence().stream().filter(owned::contains).toList();
	}

	private static String blockingReason(EventDetail detail, CalibrationEventModels.Stage stage) {
		if (stage.blockerCode() != null) return "Stage blocker: " + stage.blockerCode();
		if (!detail.blockers().isEmpty()) return "Blocking condition: " + detail.blockers().get(0);
		if (stage.state() == StageState.BLOCKED || detail.event().status() == EventStatus.BLOCKED) {
			return "Stage evidence is blocked";
		}
		return null;
	}

	private static boolean isOptional(CalibrationStageType stageType) {
		// Stage 1 process definitions declare no optional stage. Keep this explicit so
		// future optional additions cannot accidentally make every stage skippable.
		return false;
	}

	private static String evidenceBlocker(CalibrationEventModels.Stage stage) {
		if (stage.properties() == null || !stage.properties().isJsonObject()) {
			return "No accepted evidence is recorded for this stage";
		}
		var properties = stage.properties().getAsJsonObject();
		var recommendation = text(properties, "recommendation");
		return switch (stage.type()) {
		case PREREQUISITES -> requireEvidence(properties, "BATTERY_CHARGER_CONNECTIONS_CHECKED",
				"LOAD_MCB_OPEN_CONFIRMED", "SAFETY_VISUAL_INSPECTION_CONFIRMED", "OPEN_CIRCUIT_VOLTAGE_REVIEWED");
		case INITIAL_CHARGE -> first(requireRecommendation(recommendation), requireEvidence(properties,
				"CHARGE_SETPOINTS_REVIEWED", "BOOST_TARGET_REVIEWED", "FLOAT_MODE_REVIEWED"));
		case PRE_DISCHARGE_FLOAT -> requireEvidence(properties, "CHARGER_MCB_OPEN_FOR_COOLDOWN",
				"AMBIENT_TEMPERATURE_REVIEWED", "COOLDOWN_SAFETY_VALVES_REVIEWED");
		case POST_RECHARGE_FLOAT -> first(requireFloatCurrentEvidence(properties, recommendation),
				requireEvidence(properties, "RECOVERY_FLOAT_STABILITY_REVIEWED", "CELL_RESISTANCE_SAMPLE_REVIEWED",
						"CHARGER_OUTPUT_CURRENT", "MIDPOINT_VOLTAGE", "RIPPLE_BEFORE_LOAD"));
		case WAITING_FOR_LOAD -> requireEvidence(properties, "TEST_LOAD_CONNECTED");
		case DISCHARGE -> first(requireRecommendation(recommendation),
				requireEvidence(properties, "DISCHARGE_CUTOFF_REVIEWED"));
		case LOAD_DISCONNECT -> requireEvidence(properties, "TEST_LOAD_DISCONNECTED");
		case RECHARGE -> first(requireRecommendation(recommendation), requireEvidence(properties,
				"RECOVERY_CHARGE_SETPOINTS_REVIEWED", "RECOVERY_CHARGER_MCB_CLOSED_CONFIRMED"));
		case MONTHLY_INSPECTION -> first(requireFloatCurrentEvidence(properties, recommendation), requireEvidence(properties,
				"HVAC_INSPECTION", "CONNECTOR_INSPECTION", "CORROSION_INSPECTION", "CASE_INSPECTION",
				"GROUND_IMBALANCE_INSPECTION"));
		case SIX_MONTH_INSPECTION -> requireAutomaticEvidence(recommendation);
		case BASELINE_CAPTURE -> first(requireAutomaticEvidence(recommendation), requireEvidence(properties,
				"LOAD_CONNECTED_CONFIRMATION", "RIPPLE_AFTER_LOAD", "CHARGER_MCB_OPEN_CONFIRMATION",
				"VOC_BASELINE_CONFIRMATION"));
		case COMPLETION -> requireEvidence(properties, "BASELINE_SUMMARY_REVIEWED", "RETURN_TO_SERVICE_CONFIRMATION");
		};
	}

	private static String requireRecommendation(String recommendation) {
		return "READY_TO_COMPLETE".equals(recommendation) ? null : "Automatic stage evidence is not complete";
	}

	private static String requireAutomaticEvidence(String recommendation) {
		return recommendation == null || "BLOCKED".equals(recommendation)
				? "Automatic stage evidence is not complete" : null;
	}

	private static String requireFloatCurrentEvidence(com.google.gson.JsonObject properties, String recommendation) {
		return "READY_TO_COMPLETE".equals(recommendation) ? null : "Automatic Float-current evidence is not complete";
	}

	private static String requireEvidence(com.google.gson.JsonObject properties, String... types) {
		for (var type : types) {
			if (!hasEvidence(properties, type)) return "Required operator evidence is missing: " + type;
		}
		return null;
	}

	private static boolean hasEvidence(com.google.gson.JsonObject properties, String type) {
		var evidence = properties.get("manualEvidenceTypes");
		if (evidence == null || !evidence.isJsonArray()) return false;
		for (var item : evidence.getAsJsonArray()) {
			if (item.isJsonPrimitive() && type.equals(item.getAsString())) return true;
		}
		return false;
	}

	private static String text(com.google.gson.JsonObject properties, String key) {
		var value = properties.get(key);
		return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
	}

	private static String first(String... blockers) {
		for (var blocker : blockers) if (blocker != null) return blocker;
		return null;
	}

	private static AllowedAction action(ActionCode code, String label, ActionIntent intent, boolean enabled,
			String disabledReason, boolean requiresReason, boolean requiresAcknowledgement,
			List<String> acknowledgementItems) {
		return new AllowedAction(code, label, intent, enabled, enabled ? null : disabledReason, requiresReason,
				requiresAcknowledgement, acknowledgementItems);
	}
}
