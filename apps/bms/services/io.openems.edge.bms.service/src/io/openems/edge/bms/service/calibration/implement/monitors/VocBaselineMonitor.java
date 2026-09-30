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
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator.Requirements;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;

/** Captures a complete open-circuit voltage snapshot after current reaches the applied deadband. */
public final class VocBaselineMonitor implements CalibrationStageMonitor<VocBaselineMonitor.Checkpoint> {

	@Override
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis) {
		return evaluate(configuration, stageType, measurement, checkpoint, nowMillis, configuration.currentDeadbandA());
	}

	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis,
			double vocCurrentDeadbandA) {
		if (stageType != CalibrationStageType.BASELINE_CAPTURE) {
			throw new IllegalArgumentException("VocBaselineMonitor does not support " + stageType);
		}
		if (!Double.isFinite(vocCurrentDeadbandA) || vocCurrentDeadbandA < 0) {
			throw new IllegalArgumentException("vocCurrentDeadbandA must be finite and non-negative");
		}
		var blockers = new ArrayList<>(CalibrationMeasurementValidator.validate(configuration, measurement, nowMillis,
				new Requirements(true, true, false, false, false, false)));
		if (measurement != null && (measurement.stringVoltageV() == null
				|| !Double.isFinite(measurement.stringVoltageV()))
				&& !blockers.contains(CalibrationBlocker.CELL_DATA_INCOMPLETE)) {
			blockers.add(CalibrationBlocker.CELL_DATA_INCOMPLETE);
		}
		if (measurement != null && checkpoint != null) {
			var ordering = CalibrationMeasurementSequence.validate(measurement, checkpoint.snapshotGeneration(),
					checkpoint.snapshotSequence(), checkpoint.lastMeasurementTime());
			if (!ordering.isEmpty()) {
				return new CalibrationStageEvaluation<>(checkpoint, ordering, List.of(),
						List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
			}
		}
		if (!blockers.isEmpty()) {
			return new CalibrationStageEvaluation<>(checkpoint, List.copyOf(blockers), List.of(),
					List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		}
		var updated = new Checkpoint(vocCurrentDeadbandA, Math.abs(measurement.domainCurrentA()),
				measurement.stringVoltageV(), measurement.configuredCellCount(), measurement.snapshotGeneration(),
				measurement.snapshotSequence(), measurement.capturedAt());
		if (Math.abs(measurement.domainCurrentA()) > vocCurrentDeadbandA) {
			return new CalibrationStageEvaluation<>(updated, List.of(), List.of(),
					List.of(PersistenceReason.PERIODIC_SAMPLE), Recommendation.CONTINUE);
		}
		return new CalibrationStageEvaluation<>(updated, List.of(), List.of(),
				List.of(PersistenceReason.FINAL_SNAPSHOT), Recommendation.READY_TO_COMPLETE);
	}

	public record Checkpoint(double vocCurrentDeadbandA, double currentMagnitudeA, double stringVoltageV,
			int validCellCount, java.util.UUID snapshotGeneration, long snapshotSequence, long lastMeasurementTime) {
	}
}
