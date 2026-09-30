package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;

public final class CalibrationMeasurementValidator {

	private CalibrationMeasurementValidator() {
	}

	public static List<CalibrationBlocker> validate(CalibrationEventConfiguration configuration,
			CalibrationMeasurement measurement, long nowMillis, Requirements requirements) {
		var blockers = new ArrayList<CalibrationBlocker>();
		if (measurement == null) {
			return List.of(CalibrationBlocker.SNAPSHOT_MISSING);
		}
		long freshnessMillis = configuration.snapshotFreshnessSeconds() * 1000L;
		if (measurement.snapshotGeneration() == null || measurement.capturedAt() <= 0
				|| measurement.capturedAt() > nowMillis || nowMillis - measurement.capturedAt() >= freshnessMillis) {
			blockers.add(CalibrationBlocker.SNAPSHOT_STALE);
		}
		if (measurement.configuredCellCount() != configuration.cellCount()
				|| measurement.cells().size() > configuration.cellCount()) {
			blockers.add(CalibrationBlocker.CONFIGURATION_MISMATCH);
		}
		if (requirements.current()) {
			boolean currentStale = measurement.currentMeasuredAt() == null || measurement.currentMeasuredAt() <= 0
					|| measurement.currentMeasuredAt() > nowMillis
					|| nowMillis - measurement.currentMeasuredAt() >= freshnessMillis;
			if (measurement.rawCurrentA() == null || measurement.domainCurrentA() == null
					|| !Double.isFinite(measurement.rawCurrentA()) || !Double.isFinite(measurement.domainCurrentA())) {
				blockers.add(CalibrationBlocker.CURRENT_MISSING);
			}
			if (currentStale) {
				blockers.add(CalibrationBlocker.CURRENT_STALE);
			}
			if (measurement.currentQuality() != BmsCalculationSnapshot.CurrentQuality.FRESH
					&& !(currentStale && measurement.currentQuality() == BmsCalculationSnapshot.CurrentQuality.RETAINED)) {
				blockers.add(CalibrationBlocker.CURRENT_QUALITY_INVALID);
			}
		}
		if (!blockers.contains(CalibrationBlocker.CONFIGURATION_MISMATCH)) {
			for (int cellId = 1; cellId <= configuration.cellCount(); cellId++) {
				var cell = measurement.cells().get(cellId);
				if (cell == null || cell.cellId() != cellId || cell.availabilityWarning() != null
						|| requirements.voltage() && !finite(cell.voltageV())
						|| requirements.temperature() && !finite(cell.temperatureC())
						|| requirements.resistance() && cell.internalResistanceUohm() == null
						|| requirements.soc() && !finite(cell.socPct())
						|| requirements.soh() && !finite(cell.sohPct())) {
					blockers.add(CalibrationBlocker.CELL_DATA_INCOMPLETE);
					break;
				}
			}
		}
		return List.copyOf(blockers);
	}

	private static boolean finite(Double value) {
		return value != null && Double.isFinite(value);
	}

	public record Requirements(boolean current, boolean voltage, boolean temperature, boolean resistance, boolean soc,
			boolean soh) {
		public static Requirements charge() {
			return new Requirements(true, true, true, false, false, false);
		}

		public static Requirements discharge() {
			return new Requirements(true, true, true, true, false, false);
		}

		/** Cool-down is a thermal condition: require the fresh snapshot, ambient and complete cell temperatures only. */
		public static Requirements cooldown() {
			return new Requirements(false, false, true, false, false, false);
		}
	}
}
