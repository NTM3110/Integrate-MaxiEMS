package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidenceCommand;

/** Server-side allow-list for append-only operator evidence. */
public final class CalibrationManualEvidencePolicy {

	private static final Set<String> STATUSES = Set.of("RECORDED", "PASS", "FAIL", "MISSING_NOT_AVAILABLE",
			"NOT_APPLICABLE", "DATA_INVALID");
	private static final Map<CalibrationStageType, Set<String>> TYPES = types();

	private CalibrationManualEvidencePolicy() {
	}

	public static void validate(CalibrationStageType stageType, ManualEvidenceCommand command) {
		var type = normalize(command.evidenceType(), "evidenceType");
		var status = normalize(command.status(), "status");
		if (!STATUSES.contains(status)) {
			throw new IllegalArgumentException("Unsupported manual evidence status: " + status);
		}
		if (!TYPES.getOrDefault(stageType, Set.of()).contains(type)) {
			throw new IllegalArgumentException("Evidence type " + type + " is not valid for stage " + stageType);
		}
		var requiredUnit = switch (type) {
		case "RIPPLE_BEFORE_LOAD", "RIPPLE_AFTER_LOAD", "CHARGER_OUTPUT_VOLTAGE", "MIDPOINT_VOLTAGE" -> "V";
		case "CHARGER_OUTPUT_CURRENT" -> "A";
		default -> null;
		};
		if (requiredUnit != null) {
			if (command.numericValue() == null || !Double.isFinite(command.numericValue())
					|| command.numericValue() < 0) {
				throw new IllegalArgumentException(type + " requires a finite non-negative numericValue");
			}
			if (!requiredUnit.equals(command.unit())) {
				throw new IllegalArgumentException(type + " unit must be " + requiredUnit);
			}
		}
	}

	private static Map<CalibrationStageType, Set<String>> types() {
		var result = new EnumMap<CalibrationStageType, Set<String>>(CalibrationStageType.class);
		result.put(CalibrationStageType.PREREQUISITES, Set.of("BATTERY_CHARGER_CONNECTIONS_CHECKED",
				"LOAD_MCB_OPEN_CONFIRMED", "SAFETY_VISUAL_INSPECTION_CONFIRMED",
				"OPEN_CIRCUIT_VOLTAGE_REVIEWED"));
		result.put(CalibrationStageType.INITIAL_CHARGE, Set.of("CHARGE_SETPOINTS_REVIEWED",
				"BOOST_TARGET_REVIEWED", "FLOAT_MODE_REVIEWED"));
		result.put(CalibrationStageType.PRE_DISCHARGE_FLOAT, Set.of("CHARGER_MCB_OPEN_FOR_COOLDOWN",
				"AMBIENT_TEMPERATURE_REVIEWED", "COOLDOWN_SAFETY_VALVES_REVIEWED"));
		result.put(CalibrationStageType.RECHARGE, Set.of("RECOVERY_CHARGE_SETPOINTS_REVIEWED",
				"RECOVERY_CHARGER_MCB_CLOSED_CONFIRMED"));
		result.put(CalibrationStageType.POST_RECHARGE_FLOAT, Set.of("RECOVERY_FLOAT_STABILITY_REVIEWED",
				"CELL_RESISTANCE_SAMPLE_REVIEWED", "CHARGER_OUTPUT_CURRENT", "MIDPOINT_VOLTAGE",
				"RIPPLE_BEFORE_LOAD"));
		result.put(CalibrationStageType.WAITING_FOR_LOAD, Set.of("TEST_LOAD_CONNECTED"));
		result.put(CalibrationStageType.DISCHARGE, Set.of("DISCHARGE_CUTOFF_REVIEWED"));
		result.put(CalibrationStageType.LOAD_DISCONNECT, Set.of("TEST_LOAD_DISCONNECTED"));
		result.put(CalibrationStageType.BASELINE_CAPTURE, Set.of("LOAD_CONNECTED_CONFIRMATION",
				"RIPPLE_AFTER_LOAD", "CHARGER_MCB_OPEN_CONFIRMATION", "VOC_BASELINE_CONFIRMATION"));
		result.put(CalibrationStageType.MONTHLY_INSPECTION, Set.of(
				"HVAC_INSPECTION", "CONNECTOR_INSPECTION", "CORROSION_INSPECTION", "CASE_INSPECTION",
				"GROUND_IMBALANCE_INSPECTION"));
		result.put(CalibrationStageType.SIX_MONTH_INSPECTION,
				Set.of("RIPPLE_BEFORE_LOAD", "RIPPLE_AFTER_LOAD", "LINKED_PERIODIC_CAPACITY_RECORD"));
		result.put(CalibrationStageType.COMPLETION,
				Set.of("BASELINE_SUMMARY_REVIEWED", "RETURN_TO_SERVICE_CONFIRMATION"));
		return Map.copyOf(result);
	}

	private static String normalize(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
		var trimmed = value.trim();
		var normalized = trimmed.toUpperCase(Locale.ROOT);
		if (!trimmed.equals(normalized)) throw new IllegalArgumentException(name + " must use its canonical code");
		return normalized;
	}
}
