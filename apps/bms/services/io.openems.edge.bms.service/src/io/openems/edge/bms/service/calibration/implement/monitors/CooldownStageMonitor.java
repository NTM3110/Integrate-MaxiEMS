package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementSequence;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;

/**
 * Evaluates the Initial Baseline cool-down condition from one fresh, complete
 * snapshot. Cool-down is a thermal condition; it is deliberately independent
 * from the Float voltage/current observation window used by the charge stages.
 */
public final class CooldownStageMonitor implements CalibrationStageMonitor<CooldownStageMonitor.Checkpoint> {

	/** The approved maximum of max(cell temperature) minus ambient temperature. */
	public static final double ALLOWED_TEMPERATURE_DELTA_C = 0.3;

	@Override
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis) {
		if (stageType != CalibrationStageType.PRE_DISCHARGE_FLOAT) {
			throw new IllegalArgumentException("CooldownStageMonitor does not support " + stageType);
		}
		var blockers = new ArrayList<>(CalibrationMeasurementValidator.validate(configuration, measurement, nowMillis,
				CalibrationMeasurementValidator.Requirements.cooldown()));
		if (measurement != null && checkpoint != null) {
			var ordering = CalibrationMeasurementSequence.validate(measurement, checkpoint.snapshotGeneration(),
					checkpoint.snapshotSequence(), checkpoint.lastMeasurementTime());
			if (!ordering.isEmpty()) return blocked(checkpoint, ordering);
		}
		if (!blockers.isEmpty()) return blocked(checkpoint, blockers);

		var ambient = measurement.ambientTemperatureC();
		if (ambient == null || !Double.isFinite(ambient)) {
			blockers.add(CalibrationBlocker.AMBIENT_TEMPERATURE_MISSING);
			return blocked(checkpoint, blockers);
		}
		double maxCellTemperature = measurement.cells().values().stream()
				.mapToDouble(CalibrationMeasurement.Cell::temperatureC).max().orElseThrow();
		double delta = maxCellTemperature - ambient;
		var updated = new Checkpoint(ambient, maxCellTemperature, delta, measurement.snapshotGeneration(),
				measurement.snapshotSequence(), measurement.capturedAt());
		boolean withinAllowedRange = delta <= ALLOWED_TEMPERATURE_DELTA_C + 0.000001;
		return new CalibrationStageEvaluation<>(updated, List.of(), List.of(),
				withinAllowedRange ? List.of(PersistenceReason.PERIODIC_SAMPLE, PersistenceReason.FINAL_SNAPSHOT)
						: List.of(PersistenceReason.PERIODIC_SAMPLE),
				withinAllowedRange ? Recommendation.READY_TO_COMPLETE : Recommendation.CONTINUE);
	}

	private static CalibrationStageEvaluation<Checkpoint> blocked(Checkpoint checkpoint, List<CalibrationBlocker> blockers) {
		return new CalibrationStageEvaluation<>(checkpoint, blockers, List.of(),
				List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
	}

	public record Checkpoint(double ambientTemperatureC, double maxCellTemperatureC, double temperatureDeltaC,
			java.util.UUID snapshotGeneration, long snapshotSequence, long lastMeasurementTime) {
	}
}
