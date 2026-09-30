package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations;
import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations.TemperatureTargets;
import io.openems.edge.bms.service.calibration.implement.CalibrationDemoTimeScale;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementSequence;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;

/** Evaluates a confirmed Float observation window without suppressing sub-deadband current. */
public final class FloatChargeStageMonitor implements CalibrationStageMonitor<FloatChargeStageMonitor.Checkpoint> {

	@Override
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis) {
		return evaluate(configuration, stageType, measurement, checkpoint, nowMillis, CalibrationDemoTimeScale.REAL_TIME);
	}

	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis,
			int demoTimeScaleFactor) {
		CalibrationDemoTimeScale.requireSupported(demoTimeScaleFactor);
		if (stageType != CalibrationStageType.INITIAL_CHARGE && stageType != CalibrationStageType.RECHARGE
				&& stageType != CalibrationStageType.PRE_DISCHARGE_FLOAT
				&& stageType != CalibrationStageType.POST_RECHARGE_FLOAT) {
			throw new IllegalArgumentException("FloatChargeStageMonitor does not support " + stageType);
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
			return new CalibrationStageEvaluation<>(invalidate(checkpoint, measurement), blockers, List.of(),
					List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		}
		if (measurement.domainCurrentA() > configuration.currentDeadbandA()) {
			return new CalibrationStageEvaluation<>(invalidate(checkpoint, measurement),
					List.of(CalibrationBlocker.PHASE_CURRENT_INCONSISTENT), List.of(),
					List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		}
		TemperatureTargets targets = CalibrationCalculations.temperatureTargets(configuration,
				averageCellTemperature(measurement));
		double magnitude = Math.abs(measurement.domainCurrentA());
		boolean voltageInRange = Math.abs(measurement.stringVoltageV() - targets.floatStringVoltageV())
				<= configuration.floatVoltageToleranceV();
		boolean currentInRange = magnitude >= configuration.floatCurrentMinA()
				&& magnitude <= configuration.floatCurrentMaxA();
		if (!voltageInRange || !currentInRange) {
			return new CalibrationStageEvaluation<>(resetWindow(targets, checkpoint, measurement, magnitude), List.of(), List.of(),
					List.of(PersistenceReason.PERIODIC_SAMPLE), Recommendation.CONTINUE);
		}
		// A bad sample invalidates the old observation window. The next valid sample
		// starts a new window instead of silently retaining evidence from the invalid
		// one (or immediately reusing the old maximum as if no gap occurred).
		boolean restartingWindow = checkpoint != null && checkpoint.invalidWindow();
		double previousMaximum = checkpoint == null || restartingWindow ? 0.0 : checkpoint.maxFloatCurrentA();
		double maximum = Math.max(previousMaximum, magnitude);
		// The event-owned inclusive Float range is the completion policy.  Do not
		// additionally apply the historical per-stage float-current limit here: its
		// Initial Baseline default is 0.1 A and would reject the configured 0.1-0.2 A
		// stable window before the operator's immutable maximum is reached.
		var result = CalibrationCalculations.FloatCurrentResult.PASS;
		long stableSince = checkpoint == null || restartingWindow || checkpoint.stableSinceMillis() == null
				? measurement.capturedAt() : checkpoint.stableSinceMillis();
		int effectiveTimeScale = stageType == CalibrationStageType.PRE_DISCHARGE_FLOAT
				? CalibrationDemoTimeScale.REAL_TIME : demoTimeScaleFactor;
		long stableDurationMillis = CalibrationDemoTimeScale.scaledElapsedMillis(measurement.capturedAt() - stableSince,
				effectiveTimeScale);
		var updated = new Checkpoint(targets, maximum, result,
				checkpoint == null || restartingWindow ? 1 : checkpoint.sampleCount() + 1,
				false, stableSince, stableDurationMillis, measurement.snapshotGeneration(), measurement.snapshotSequence(), measurement.capturedAt());
		var persistence = new ArrayList<PersistenceReason>();
		persistence.add(PersistenceReason.PERIODIC_SAMPLE);
		if (maximum > previousMaximum) {
			persistence.add(PersistenceReason.CURRENT_PEAK);
		}
		boolean stable = stableDurationMillis >= configuration.floatStableDurationMinutes() * 60_000L;
		if (stable) persistence.add(PersistenceReason.FINAL_SNAPSHOT);
		return new CalibrationStageEvaluation<>(updated, List.of(), List.of(), persistence,
				stable ? Recommendation.READY_TO_COMPLETE : Recommendation.CONTINUE);
	}

	private static Checkpoint resetWindow(TemperatureTargets targets, Checkpoint checkpoint, CalibrationMeasurement measurement,
			double magnitude) {
		return new Checkpoint(targets, magnitude, CalibrationCalculations.FloatCurrentResult.DATA_INVALID, checkpoint == null ? 1 : checkpoint.sampleCount() + 1,
				false, null, 0, measurement.snapshotGeneration(), measurement.snapshotSequence(), measurement.capturedAt());
	}

	private static Checkpoint invalidate(Checkpoint checkpoint, CalibrationMeasurement measurement) {
		if (checkpoint == null) {
			return null;
		}
		return new Checkpoint(checkpoint.targets(), checkpoint.maxFloatCurrentA(), CalibrationCalculations.FloatCurrentResult.DATA_INVALID,
				checkpoint.sampleCount(), true, null, 0,
				measurement == null ? checkpoint.snapshotGeneration() : measurement.snapshotGeneration(),
				measurement == null ? checkpoint.snapshotSequence() : measurement.snapshotSequence(),
				measurement == null ? checkpoint.lastMeasurementTime() : measurement.capturedAt());
	}

	private static double averageCellTemperature(CalibrationMeasurement measurement) {
		return measurement.cells().values().stream().mapToDouble(CalibrationMeasurement.Cell::temperatureC).average()
				.orElseThrow();
	}

	public record Checkpoint(TemperatureTargets targets, double maxFloatCurrentA, CalibrationCalculations.FloatCurrentResult result,
			int sampleCount, boolean invalidWindow, Long stableSinceMillis, long stableDurationMillis,
			java.util.UUID snapshotGeneration, long snapshotSequence,
			long lastMeasurementTime) {
	}
}
