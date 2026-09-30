package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;

import java.util.List;
import java.util.UUID;

/** Shared generation/sequence/time ordering contract for non-discharge monitors. */
public final class CalibrationMeasurementSequence {

	private CalibrationMeasurementSequence() {
	}

	public static List<CalibrationBlocker> validate(CalibrationMeasurement measurement, UUID previousGeneration,
			long previousSequence, long previousCapturedAt) {
		if (previousGeneration == null) return List.of();
		if (measurement.capturedAt() < previousCapturedAt) {
			return List.of(CalibrationBlocker.MEASUREMENT_TIME_REGRESSED);
		}
		if (!previousGeneration.equals(measurement.snapshotGeneration())) return List.of();
		if (measurement.snapshotSequence() == previousSequence) {
			return List.of(CalibrationBlocker.MEASUREMENT_SEQUENCE_REPEATED);
		}
		if (measurement.snapshotSequence() < previousSequence) {
			return List.of(CalibrationBlocker.MEASUREMENT_SEQUENCE_REGRESSED);
		}
		return List.of();
	}
}
