package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.List;

import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations;
import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations.TemperatureTargets;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementSequence;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;

/** Evaluates charge telemetry consistency and temperature-compensated targets. */
public final class ChargeStageMonitor implements CalibrationStageMonitor<ChargeStageMonitor.Checkpoint> {
	/*
	 * Cell voltage channels are published at 1 mV resolution. The compensated
	 * string target is calculated as a double, so an otherwise exact endpoint can
	 * be lower by at most half one cell-channel step per configured cell after
	 * quantization. Without this allowance the next (current-tapered) Float row is
	 * evaluated as if Boost were still active and is then correctly rejected by
	 * the charge-current phase guard, leaving the Boost process BLOCKED forever.
	 */
	private static final double CELL_VOLTAGE_QUANTIZATION_HALF_STEP_V = 0.0005;

	@Override
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis) {
		if (stageType != CalibrationStageType.INITIAL_CHARGE && stageType != CalibrationStageType.RECHARGE) {
			throw new IllegalArgumentException("ChargeStageMonitor does not support " + stageType);
		}
		var blockers = CalibrationMeasurementValidator.validate(configuration, measurement, nowMillis,
				CalibrationMeasurementValidator.Requirements.charge());
		if (measurement != null && checkpoint != null) {
			var ordering = CalibrationMeasurementSequence.validate(measurement, checkpoint.snapshotGeneration(),
					checkpoint.snapshotSequence(), checkpoint.lastMeasurementTime());
			if (!ordering.isEmpty()) {
				return new CalibrationStageEvaluation<>(checkpoint, ordering, List.of(),
						List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
			}
		}
		if (!blockers.isEmpty()) {
			return new CalibrationStageEvaluation<>(observe(checkpoint, measurement), blockers, List.of(),
					List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		}
		if (measurement.domainCurrentA() >= -configuration.currentDeadbandA()) {
			return new CalibrationStageEvaluation<>(checkpoint, List.of(CalibrationBlocker.PHASE_CURRENT_INCONSISTENT),
					List.of(), List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		}
		TemperatureTargets targets = CalibrationCalculations.temperatureTargets(configuration,
				averageCellTemperature(measurement));
		double magnitude = Math.abs(measurement.domainCurrentA());
		long startedAt = checkpoint == null ? measurement.capturedAt() : checkpoint.startedAt();
		long durationMillis = measurement.capturedAt() - startedAt;
		var updated = new Checkpoint(targets, Math.max(checkpoint == null ? 0.0 : checkpoint.maxChargeCurrentA(),
				magnitude), startedAt, durationMillis, measurement.snapshotGeneration(), measurement.snapshotSequence(),
				measurement.capturedAt());
		if (durationMillis <= 0) {
			return new CalibrationStageEvaluation<>(updated, List.of(), List.of(),
					List.of(PersistenceReason.PERIODIC_SAMPLE), Recommendation.CONTINUE);
		}
		double quantizationAllowanceV = configuration.cellCount() * CELL_VOLTAGE_QUANTIZATION_HALF_STEP_V;
		if (measurement.stringVoltageV() + quantizationAllowanceV < targets.boostStringVoltageV()) {
			return new CalibrationStageEvaluation<>(updated, List.of(), List.of(), List.of(PersistenceReason.PERIODIC_SAMPLE),
					Recommendation.CONTINUE);
		}
		return new CalibrationStageEvaluation<>(updated, List.of(), List.of(), List.of(PersistenceReason.PERIODIC_SAMPLE,
				PersistenceReason.FINAL_SNAPSHOT),
				Recommendation.READY_TO_COMPLETE);
	}

	private static double averageCellTemperature(CalibrationMeasurement measurement) {
		return measurement.cells().values().stream().mapToDouble(CalibrationMeasurement.Cell::temperatureC).average()
				.orElseThrow();
	}

	private static Checkpoint observe(Checkpoint checkpoint, CalibrationMeasurement measurement) {
		if (checkpoint == null || measurement == null) return checkpoint;
		return new Checkpoint(checkpoint.targets(), checkpoint.maxChargeCurrentA(), checkpoint.startedAt(),
				checkpoint.durationMillis(),
				measurement.snapshotGeneration(), measurement.snapshotSequence(), measurement.capturedAt());
	}

	public record Checkpoint(TemperatureTargets targets, double maxChargeCurrentA, long startedAt, long durationMillis,
			java.util.UUID snapshotGeneration, long snapshotSequence, long lastMeasurementTime) {
	}
}
