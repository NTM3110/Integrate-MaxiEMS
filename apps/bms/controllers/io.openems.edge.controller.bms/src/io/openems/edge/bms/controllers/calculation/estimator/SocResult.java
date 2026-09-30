package io.openems.edge.bms.controllers.calculation.estimator;

/** Immutable per-cell SoC result with updated in-memory state. */
public record SocResult(Double valuePct, Method method, Quality quality, String note,
		PerCellEstimatorState state) {

	public enum Method {
		BASELINE_COULOMB_COUNTING,
		BASELINE_VOLTAGE_BOOTSTRAP,
		STABLE_FLOAT_REANCHOR,
		VOLTAGE_FALLBACK,
		RETAINED,
		UNAVAILABLE
	}

	public enum Quality {
		VALID,
		ESTIMATED,
		RETAINED,
		UNAVAILABLE
	}
}
