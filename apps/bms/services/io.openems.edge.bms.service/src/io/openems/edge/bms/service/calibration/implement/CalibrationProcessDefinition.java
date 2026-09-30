package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.List;

/** Pure ordered process definitions; stage monitors never mutate these flows. */
public final class CalibrationProcessDefinition {

	public enum SamplingPolicy {
		NONE, FINAL_SNAPSHOT, PERIODIC, CONTINUOUS
	}

	/**
	 * A process is an operator-started automatic activity inside a persisted
	 * stage. Checklist-only work deliberately has no process definition.
	 */
	public record Process(String type, SamplingPolicy samplingPolicy) {
	}

	private CalibrationProcessDefinition() {
	}

	public static List<CalibrationStageType> stages(CalibrationEventType eventType) {
		return switch (eventType) {
		case INITIAL_BASELINE -> List.of(CalibrationStageType.PREREQUISITES,
				CalibrationStageType.INITIAL_CHARGE, CalibrationStageType.PRE_DISCHARGE_FLOAT,
				CalibrationStageType.WAITING_FOR_LOAD, CalibrationStageType.DISCHARGE,
				CalibrationStageType.LOAD_DISCONNECT, CalibrationStageType.RECHARGE,
				CalibrationStageType.POST_RECHARGE_FLOAT, CalibrationStageType.BASELINE_CAPTURE,
				CalibrationStageType.COMPLETION);
		case MONTHLY_INSPECTION -> List.of(CalibrationStageType.PREREQUISITES,
				CalibrationStageType.MONTHLY_INSPECTION, CalibrationStageType.COMPLETION);
		case SIX_MONTH_INSPECTION -> stages(CalibrationEventType.INITIAL_BASELINE);
		case PERIODIC_DISCHARGE -> List.of(CalibrationStageType.PREREQUISITES,
				CalibrationStageType.PRE_DISCHARGE_FLOAT, CalibrationStageType.WAITING_FOR_LOAD,
				CalibrationStageType.DISCHARGE, CalibrationStageType.LOAD_DISCONNECT,
				CalibrationStageType.RECHARGE, CalibrationStageType.POST_RECHARGE_FLOAT,
				CalibrationStageType.COMPLETION);
		};
	}

	/**
	 * Maps the accepted Initial Baseline UI to backend-owned physical work. A
	 * process is created only for an explicit "Start process" activity.
	 */
	public static List<Process> processes(CalibrationEventType eventType, CalibrationStageType stageType) {
		if (eventType != CalibrationEventType.INITIAL_BASELINE
				&& eventType != CalibrationEventType.SIX_MONTH_INSPECTION) {
			return List.of();
		}
		return switch (stageType) {
		case INITIAL_CHARGE -> List.of(new Process("BOOST_CHARGE_TO_VBOOST", SamplingPolicy.FINAL_SNAPSHOT),
				new Process("FLOAT_SETTLEMENT_TO_VFLOAT", SamplingPolicy.FINAL_SNAPSHOT));
		case PRE_DISCHARGE_FLOAT -> List.of(new Process("COOL_DOWN_TO_AMBIENT", SamplingPolicy.FINAL_SNAPSHOT));
		case DISCHARGE -> List.of(new Process("CONTROLLED_DISCHARGE", SamplingPolicy.CONTINUOUS));
		case RECHARGE -> List.of(new Process("RECOVERY_CHARGE_TO_VBOOST", SamplingPolicy.FINAL_SNAPSHOT));
		case POST_RECHARGE_FLOAT -> List.of(new Process("RECOVERY_FLOAT_STABILIZATION", SamplingPolicy.FINAL_SNAPSHOT));
		case BASELINE_CAPTURE -> List.of(new Process("VOC_BASELINE_CAPTURE", SamplingPolicy.FINAL_SNAPSHOT));
		default -> List.of();
		};
	}
}
