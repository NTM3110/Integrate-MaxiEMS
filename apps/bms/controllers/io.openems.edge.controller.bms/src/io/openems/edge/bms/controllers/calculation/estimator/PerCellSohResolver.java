package io.openems.edge.bms.controllers.calculation.estimator;

import io.openems.edge.bms.controllers.calculation.legacy.SoHEngine;
import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

/** Pure precedence rules for one cell's SoH. */
public final class PerCellSohResolver {

	private static final String BASELINE_NOTE = "Authoritative SoH from the published Initial Baseline";
	private static final String RESISTANCE_NOTE = "Resistance estimate; complete and publish an Initial Baseline";
	private static final String IN_PROGRESS_NOTE = "Initial Baseline discharge in progress; value is not authoritative";

	private PerCellSohResolver() {
	}

	public static SohResult resolve(int cellIndex, double ratedCapacityAh, int ratedResistanceUohm,
			Double resistanceUohm, InitialBaselineReferenceDataset baseline,
			ActiveCalibrationEstimatorContext active) {
		if (baseline != null) {
			var capacity = baseline.cellCapacityReferences().get(cellIndex);
			if (!validPublishedBaseline(baseline) || capacity == null
					|| !validCapacity(capacity.measuredCellCapacityAh()) || !validCapacity(ratedCapacityAh)) {
				return unavailable("Published Initial Baseline has no valid capacity for cell " + cellIndex,
						baseline.calibrationEventId(), baseline.curve() == null ? null : baseline.curve().calibrationProcessId());
			}
			return new SohResult(clampPercent(capacity.measuredCellCapacityAh() / ratedCapacityAh * 100d),
					SohResult.Method.AUTHORITATIVE_BASELINE, SohResult.Quality.AUTHORITATIVE, BASELINE_NOTE,
					baseline.calibrationEventId(), baseline.curve() == null ? null : baseline.curve().calibrationProcessId());
		}

		if (active != null && active.initialBaselineDischarge()) {
			var progress = active.cells().get(cellIndex);
			if (progress != null && progress.cutoffReached()) {
				if (!validCapacity(progress.cutoffCapacityAh()) || !validCapacity(ratedCapacityAh)) {
					return unavailable("Initial Baseline cutoff capacity is invalid", active.calibrationEventId(),
							active.calibrationProcessId());
				}
				return new SohResult(clampPercent(progress.cutoffCapacityAh() / ratedCapacityAh * 100d),
						SohResult.Method.IN_PROGRESS_CAPACITY, SohResult.Quality.PROVISIONAL, IN_PROGRESS_NOTE,
						active.calibrationEventId(), active.calibrationProcessId());
			}
			var estimate = resistanceEstimate(resistanceUohm, ratedResistanceUohm);
			return estimate == null
					? unavailable("Resistance input is unavailable during Initial Baseline discharge",
							active.calibrationEventId(), active.calibrationProcessId())
					: new SohResult(estimate, SohResult.Method.IN_PROGRESS, SohResult.Quality.PROVISIONAL,
							IN_PROGRESS_NOTE, active.calibrationEventId(), active.calibrationProcessId());
		}

		var estimate = resistanceEstimate(resistanceUohm, ratedResistanceUohm);
		return estimate == null ? unavailable("Resistance input is unavailable; complete an Initial Baseline", null, null)
				: new SohResult(estimate, SohResult.Method.RESISTANCE_ESTIMATE, SohResult.Quality.ESTIMATED,
						RESISTANCE_NOTE, null, null);
	}

	private static Double resistanceEstimate(Double resistanceUohm, int ratedResistanceUohm) {
		if (resistanceUohm == null || !Double.isFinite(resistanceUohm) || resistanceUohm <= 0
				|| ratedResistanceUohm <= 0) return null;
		var value = SoHEngine.updatedSoHRegular(resistanceUohm, ratedResistanceUohm) * 100d;
		return Double.isFinite(value) ? value : null;
	}

	private static boolean validCapacity(Double value) {
		return value != null && Double.isFinite(value) && value > 0;
	}

	private static boolean validPublishedBaseline(InitialBaselineReferenceDataset baseline) {
		return baseline.completedAt() != null && baseline.approvedAt() != null && baseline.approvedBy() != null
				&& baseline.curve() != null && baseline.curve().publishedAt() != null
				&& baseline.curve().contentHash() != null && !baseline.curve().contentHash().isBlank();
	}

	private static double clampPercent(double value) {
		return Math.max(0d, Math.min(100d, value));
	}

	private static SohResult unavailable(String note, Long eventId, Long processId) {
		return new SohResult(null, SohResult.Method.UNAVAILABLE, SohResult.Quality.UNAVAILABLE, note, eventId, processId);
	}
}
