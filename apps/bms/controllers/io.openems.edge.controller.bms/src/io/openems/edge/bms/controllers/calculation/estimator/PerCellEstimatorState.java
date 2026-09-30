package io.openems.edge.bms.controllers.calculation.estimator;

/** In-memory state for one per-cell coulomb-counting estimator. */
public record PerCellEstimatorState(double socPct, Long currentMeasuredAt, String referenceIdentity) {
	public PerCellEstimatorState {
		if (!Double.isFinite(socPct) || socPct < 0 || socPct > 100) {
			throw new IllegalArgumentException("socPct must be within [0,100]");
		}
	}
}
