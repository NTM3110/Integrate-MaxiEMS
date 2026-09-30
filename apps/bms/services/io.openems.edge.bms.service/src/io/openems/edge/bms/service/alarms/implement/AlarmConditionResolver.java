package io.openems.edge.bms.service.alarms.implement;

import java.util.Comparator;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmDetail;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Observation;

/** Resolves raise/clear/suspend using the frame and lifecycle hysteresis. */
public final class AlarmConditionResolver {

	public record Result(Observation observation, Double observed, Double threshold, String reason) {
	}

	private AlarmConditionResolver() {
	}

	public static Result resolve(BmsMonitoringSnapshot frame, Alarm alarm, Integer cellIndex,
			Double previousThreshold, boolean raised, AlarmLifecyclePolicy.Rule rule) {
		/*
		 * Quality and applicability are authoritative lifecycle gates. Do not trust an
		 * alarm detail from a degraded frame or from a producer that accidentally
		 * emitted a current alarm while its operating mode was unavailable.
		 */
		if (alarm == Alarm.SENSOR_MODULE_FAILURE) {
			var active = activeDetail(frame, alarm, cellIndex);
			if (active != null) {
				return new Result(Observation.ACTIVE, active.observedValue(), active.thresholdValue(), active.reason());
			}
			if (degraded(frame)) {
				return new Result(Observation.ACTIVE, null, previousThreshold, "Frame or current quality is degraded");
			}
			return new Result(Observation.CLEAR, null, previousThreshold, "Frame and module quality recovered");
		}
		if (degraded(frame)) {
			return new Result(Observation.SUSPENDED, null, previousThreshold, "Frame quality is degraded");
		}
		if (frame.alarmEvaluations().get(alarm) == BmsMonitoringSnapshot.AlarmEvaluation.UNAVAILABLE) {
			return new Result(Observation.SUSPENDED, null, previousThreshold,
					"Required reference or input is unavailable");
		}
		if (alarm == Alarm.FLOAT_CHARGE_OVERCURRENT || alarm == Alarm.CHARGE_OVERCURRENT) {
			var mode = frame.operatingMode();
			if (mode.quality() != ModeQuality.VALID || mode.mode() == OperatingMode.UNKNOWN) {
				return new Result(Observation.SUSPENDED, null, previousThreshold,
						"Operating mode is unavailable; current alarm is suspended");
			}
			boolean applicable = alarm == Alarm.FLOAT_CHARGE_OVERCURRENT
					? mode.mode() == OperatingMode.FLOAT
					: mode.mode() == OperatingMode.FLOAT || mode.mode() == OperatingMode.CHARGING;
			if (!applicable) {
				return new Result(Observation.CLEAR, frame.string().batteryCurrentA(), previousThreshold,
						"Operating mode is outside alarm applicability");
			}
		}
		var active = activeDetail(frame, alarm, cellIndex);
		if (active != null) {
			return new Result(Observation.ACTIVE, active.observedValue(), active.thresholdValue(), active.reason());
		}
		Double observed = observed(frame, alarm, cellIndex);
		Double appliedThreshold = threshold(frame, alarm, cellIndex);
		Double threshold = finite(appliedThreshold) ? appliedThreshold : previousThreshold;
		if (!finite(observed) || !finite(threshold)) {
			return new Result(Observation.SUSPENDED, observed, threshold, "Required measurement or threshold is unavailable");
		}
		if (!raised) {
			return new Result(Observation.CLEAR, observed, threshold, "Raise condition is no longer active");
		}
		double margin = rule.hysteresisUnit() == AlarmLifecyclePolicy.HysteresisUnit.ABSOLUTE
				? rule.hysteresis() : Math.abs(threshold) * rule.hysteresis();
		boolean clear = isLowAlarm(alarm) ? observed >= threshold + margin : observed <= threshold - margin;
		return new Result(clear ? Observation.CLEAR : Observation.ACTIVE, observed, threshold,
				clear ? "Condition cleared beyond hysteresis" : "Condition remains inside hysteresis band");
	}

	private static AlarmDetail activeDetail(BmsMonitoringSnapshot frame, Alarm alarm, Integer cellIndex) {
		return frame.alarmDetails().stream()
				.filter(detail -> detail.alarm() == alarm && equals(detail.cellIndex(), cellIndex))
				.max(Comparator.comparingDouble(AlarmConditionResolver::severity)).orElse(null);
	}

	private static double severity(AlarmDetail detail) {
		if (!finite(detail.observedValue()) || !finite(detail.thresholdValue())) return 0;
		return isLowAlarm(detail.alarm()) ? detail.thresholdValue() - detail.observedValue()
				: detail.observedValue() - detail.thresholdValue();
	}

	private static boolean degraded(BmsMonitoringSnapshot frame) {
		return frame.stale() || frame.currentQuality() != CurrentQuality.FRESH;
	}

	private static Double observed(BmsMonitoringSnapshot frame, Alarm alarm, Integer cellIndex) {
		var value = frame.string();
		return switch (alarm) {
		case LOW_CELL_VOLTAGE, HIGH_CELL_VOLTAGE -> cell(frame, cellIndex) == null ? null
				: cell(frame, cellIndex).voltageV();
		case BATTERY_VOLTAGE_LOW, BATTERY_VOLTAGE_HIGH -> value.batteryVoltageV();
		case CELL_RESISTANCE -> cell(frame, cellIndex) == null ? null : cell(frame, cellIndex).resistanceUohm();
		case CELL_TEMPERATURE -> cell(frame, cellIndex) == null ? null : cell(frame, cellIndex).temperatureC();
		case FLOAT_CHARGE_OVERCURRENT, CHARGE_OVERCURRENT -> value.batteryCurrentA() != null
				&& value.batteryCurrentA() < 0 ? -value.batteryCurrentA() : 0.0;
		case LOW_SOC -> value.batterySocPct();
		case HIGH_AMBIENT_TEMPERATURE -> value.ambientTemperatureC();
		case MIDPOINT_VOLTAGE -> value.midpointVoltageV() == null || value.batteryVoltageV() == null ? null
				: Math.abs(value.midpointVoltageV() - value.batteryVoltageV() / 2.0);
		case SENSOR_MODULE_FAILURE -> null;
		};
	}

	private static Double threshold(BmsMonitoringSnapshot frame, Alarm alarm, Integer cellIndex) {
		var threshold = frame.thresholds();
		var value = frame.string();
		return switch (alarm) {
		case LOW_CELL_VOLTAGE -> value.averageCellVoltageV() == null ? null
				: value.averageCellVoltageV() * (1.0 - threshold.lowCellDeviationFraction());
		case HIGH_CELL_VOLTAGE -> value.averageCellVoltageV() == null ? null
				: value.averageCellVoltageV() * (1.0 + threshold.highCellDeviationFraction());
		case BATTERY_VOLTAGE_LOW -> threshold.batteryVoltageLowV();
		case BATTERY_VOLTAGE_HIGH -> threshold.batteryVoltageHighV();
		case CELL_RESISTANCE -> cell(frame, cellIndex) == null ? null
				: cell(frame, cellIndex).resistanceAlarmThresholdUohm();
		case CELL_TEMPERATURE -> value.averageCellTemperatureC() == null || value.ambientTemperatureC() == null
				? null : Math.min(value.averageCellTemperatureC() + threshold.cellTemperatureAboveAverageC(),
						value.ambientTemperatureC() + threshold.cellTemperatureAboveAmbientC());
		case FLOAT_CHARGE_OVERCURRENT -> threshold.floatChargeOvercurrentA();
		case CHARGE_OVERCURRENT -> threshold.chargeOvercurrentA();
		case LOW_SOC -> threshold.lowSocPct();
		case HIGH_AMBIENT_TEMPERATURE -> threshold.highAmbientTemperatureC();
		case MIDPOINT_VOLTAGE -> threshold.midpointImbalanceV();
		case SENSOR_MODULE_FAILURE -> null;
		};
	}

	private static BmsMonitoringSnapshot.CellMeasurements cell(BmsMonitoringSnapshot frame, Integer cellIndex) {
		return cellIndex == null ? null : frame.cells().get(cellIndex);
	}

	private static boolean isLowAlarm(Alarm alarm) {
		return alarm == Alarm.LOW_CELL_VOLTAGE || alarm == Alarm.BATTERY_VOLTAGE_LOW || alarm == Alarm.LOW_SOC;
	}

	private static boolean equals(Integer left, Integer right) {
		return left == null ? right == null : left.equals(right);
	}

	private static boolean finite(Double value) {
		return value != null && Double.isFinite(value);
	}
}
