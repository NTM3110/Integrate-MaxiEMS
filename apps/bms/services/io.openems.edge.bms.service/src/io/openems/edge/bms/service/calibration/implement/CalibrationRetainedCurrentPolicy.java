package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;

/** Prevents retained calculator publications from recounting one TC measurement. */
public final class CalibrationRetainedCurrentPolicy {

	private CalibrationRetainedCurrentPolicy() {
	}

	public static boolean shouldSkip(CalibrationEventConfiguration configuration, CalibrationMeasurement measurement,
			long evaluatedAtMillis, Long lastCurrentMeasuredAt, boolean currentStaleAlreadyPersisted) {
		if (measurement.currentQuality() != CurrentQuality.RETAINED || measurement.currentMeasuredAt() == null
				|| !measurement.currentMeasuredAt().equals(lastCurrentMeasuredAt)) {
			return false;
		}
		long freshnessMillis = configuration.snapshotFreshnessSeconds() * 1_000L;
		boolean stale = measurement.currentMeasuredAt() <= 0 || measurement.currentMeasuredAt() > evaluatedAtMillis
				|| evaluatedAtMillis - measurement.currentMeasuredAt() >= freshnessMillis;
		return !stale || currentStaleAlreadyPersisted;
	}
}
