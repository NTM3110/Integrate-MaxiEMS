package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations;
import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations.FloatCurrentResult;
import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations.TemperatureTargets;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementSequence;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator.Requirements;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;

/** Captures automatic inspection evidence; manual checklists remain separate evidence. */
public final class InspectionStageMonitor implements CalibrationStageMonitor<InspectionStageMonitor.Checkpoint> {

	@Override
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis) {
		return evaluate(configuration, stageType, measurement, checkpoint, nowMillis, null, null, null);
	}

	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis,
			Long previousMonthlyEventId, Double previousMonthlyFloatCurrentA, Long previousMonthlyMeasuredAt) {
		Requirements requirements = switch (stageType) {
		case PREREQUISITES -> new Requirements(true, true, true, false, false, false);
		case MONTHLY_INSPECTION -> new Requirements(true, true, true, false, false, false);
		case SIX_MONTH_INSPECTION -> new Requirements(true, true, true, true, true, true);
		case BASELINE_CAPTURE -> new Requirements(true, true, true, true, true, true);
		default -> throw new IllegalArgumentException("InspectionStageMonitor does not support " + stageType);
		};
		var blockers = new ArrayList<>(CalibrationMeasurementValidator.validate(configuration, measurement, nowMillis,
				requirements));
		if (stageType == CalibrationStageType.MONTHLY_INSPECTION && measurement != null
				&& monthlyScalarDataIncomplete(measurement)
				&& !blockers.contains(CalibrationBlocker.CELL_DATA_INCOMPLETE)) {
			blockers.add(CalibrationBlocker.CELL_DATA_INCOMPLETE);
		}
		if (stageType == CalibrationStageType.BASELINE_CAPTURE && measurement != null
				&& baselineStringDataIncomplete(measurement)
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
			return new CalibrationStageEvaluation<>(invalidateFloatWindow(checkpoint, measurement), blockers, List.of(),
					List.of(PersistenceReason.QUALITY_CHANGE),
					Recommendation.BLOCKED);
		}
		double averageTemperature = measurement.cells().values().stream()
				.mapToDouble(CalibrationMeasurement.Cell::temperatureC).average().orElseThrow();
		TemperatureTargets targets = CalibrationCalculations.temperatureTargets(configuration, averageTemperature);
		FloatCurrentResult floatCurrentResult = null;
		Double maxFloatCurrentA = checkpoint == null ? null : checkpoint.maxFloatCurrentA();
		Double midpointVoltageV = null;
		Double floatCurrentA = null;
		Boolean floatCurrentPass = null;
		Double relativeDeviationPercent = null;
		ComparisonResult comparisonResult = ComparisonResult.UNAVAILABLE;
		Boolean ambientTemperaturePass = null;
		if (stageType == CalibrationStageType.MONTHLY_INSPECTION) {
			midpointVoltageV = measurement.midpointVoltageV();
			var magnitude = Math.abs(measurement.domainCurrentA());
			floatCurrentA = magnitude;
			floatCurrentPass = magnitude < configuration.periodicFloatCurrentLimitA();
			// Monthly is a live point-in-time inspection. A later conforming sample must
			// clear an earlier current violation; retaining a stage-wide maximum would make
			// the displayed latest PASS disagree with the authoritative blocker.
			maxFloatCurrentA = magnitude;
			floatCurrentResult = CalibrationCalculations.evaluateFloatCurrent(maxFloatCurrentA,
					configuration.periodicFloatCurrentLimitA(), configuration.floatOvercurrentReferenceA());
			if (previousMonthlyFloatCurrentA != null && Double.isFinite(previousMonthlyFloatCurrentA)) {
				var previousMagnitude = Math.abs(previousMonthlyFloatCurrentA);
				if (previousMagnitude > 0) {
					relativeDeviationPercent = Math.abs(magnitude - previousMagnitude) / previousMagnitude * 100.0;
					comparisonResult = relativeDeviationPercent <= 30.0 ? ComparisonResult.PASS : ComparisonResult.FAIL;
				} else {
					comparisonResult = ComparisonResult.NOT_COMPARABLE_ZERO;
				}
			}
			ambientTemperaturePass = measurement.ambientTemperatureC() != null
					&& measurement.ambientTemperatureC() <= 33.0;
		}
		var updated = new Checkpoint(targets, measurement.stringVoltageV(), measurement.stringSocPct(),
				measurement.stringSohPct(), maxFloatCurrentA, floatCurrentResult, false,
				measurement.snapshotGeneration(), measurement.snapshotSequence(), measurement.capturedAt(), midpointVoltageV,
				floatCurrentA, floatCurrentPass, previousMonthlyEventId, previousMonthlyFloatCurrentA,
				previousMonthlyMeasuredAt, relativeDeviationPercent, comparisonResult, measurement.ambientTemperatureC(),
				ambientTemperaturePass);
		var resultBlockers = new ArrayList<CalibrationBlocker>();
		if (floatCurrentResult != null && floatCurrentResult != FloatCurrentResult.PASS) {
			resultBlockers.add(CalibrationBlocker.FLOAT_CURRENT_CHECK_FAILED);
		}
		if (comparisonResult == ComparisonResult.FAIL) {
			resultBlockers.add(CalibrationBlocker.MONTHLY_DEVIATION_EXCEEDED);
		}
		if (Boolean.FALSE.equals(ambientTemperaturePass)) {
			resultBlockers.add(CalibrationBlocker.AMBIENT_TEMPERATURE_HIGH);
		}
		var persistenceReason = stageType == CalibrationStageType.BASELINE_CAPTURE
				? PersistenceReason.FINAL_SNAPSHOT : PersistenceReason.PERIODIC_SAMPLE;
		return new CalibrationStageEvaluation<>(updated, resultBlockers, List.of(),
				List.of(persistenceReason),
				resultBlockers.isEmpty() ? Recommendation.READY_TO_COMPLETE : Recommendation.BLOCKED);
	}

	private static Checkpoint invalidateFloatWindow(Checkpoint checkpoint, CalibrationMeasurement measurement) {
		if (checkpoint == null) {
			return null;
		}
		return new Checkpoint(checkpoint.targets(), checkpoint.stringVoltageV(), checkpoint.stringSocPct(),
				checkpoint.resistanceEstimatedStringSohPct(), checkpoint.maxFloatCurrentA(),
				FloatCurrentResult.DATA_INVALID, true,
				measurement == null ? checkpoint.snapshotGeneration() : measurement.snapshotGeneration(),
				measurement == null ? checkpoint.snapshotSequence() : measurement.snapshotSequence(),
				measurement == null ? checkpoint.lastMeasurementTime() : measurement.capturedAt(),
				checkpoint.midpointVoltageV(), checkpoint.floatCurrentA(), checkpoint.floatCurrentPass(),
				checkpoint.previousMonthlyEventId(), checkpoint.previousMonthlyFloatCurrentA(),
				checkpoint.previousMonthlyMeasuredAt(), checkpoint.relativeDeviationPercent(),
				checkpoint.comparisonResult(), checkpoint.ambientTemperatureC(), checkpoint.ambientTemperaturePass());
	}

	private static boolean monthlyScalarDataIncomplete(CalibrationMeasurement measurement) {
		if (measurement.ambientTemperatureC() == null || !Double.isFinite(measurement.ambientTemperatureC())
				|| measurement.stringVoltageV() == null || !Double.isFinite(measurement.stringVoltageV())
				|| measurement.midpointVoltageV() == null || !Double.isFinite(measurement.midpointVoltageV())) {
			return true;
		}
		return false;
	}

	private static boolean baselineStringDataIncomplete(CalibrationMeasurement measurement) {
		return !finite(measurement.ambientTemperatureC()) || !finite(measurement.stringVoltageV())
				|| !finite(measurement.midpointVoltageV()) || !finite(measurement.averageVoltageV())
				|| !finite(measurement.averageTemperatureC()) || !finite(measurement.averageResistanceUohm())
				|| !finite(measurement.stringSocPct()) || !finite(measurement.stringSohPct());
	}

	private static boolean finite(Double value) {
		return value != null && Double.isFinite(value);
	}

	public record Checkpoint(TemperatureTargets targets, Double stringVoltageV, Double stringSocPct,
			Double resistanceEstimatedStringSohPct, Double maxFloatCurrentA,
			FloatCurrentResult floatCurrentResult, boolean invalidFloatWindow, java.util.UUID snapshotGeneration,
			long snapshotSequence, long lastMeasurementTime, Double midpointVoltageV, Double floatCurrentA,
			Boolean floatCurrentPass, Long previousMonthlyEventId, Double previousMonthlyFloatCurrentA,
			Long previousMonthlyMeasuredAt, Double relativeDeviationPercent, ComparisonResult comparisonResult,
			Double ambientTemperatureC, Boolean ambientTemperaturePass) {
	}

	public enum ComparisonResult {
		PASS,
		FAIL,
		UNAVAILABLE,
		NOT_COMPARABLE_ZERO
	}
}
