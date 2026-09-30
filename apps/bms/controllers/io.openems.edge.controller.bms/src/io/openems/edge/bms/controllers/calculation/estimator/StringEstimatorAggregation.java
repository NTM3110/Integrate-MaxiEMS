package io.openems.edge.bms.controllers.calculation.estimator;

/** Approved conservative string aggregation: minimum valid cell value. */
public final class StringEstimatorAggregation {

	private StringEstimatorAggregation() {
	}

	public static Double minimumValid(Double[] values) {
		Double minimum = null;
		for (var value : values) {
			if (value == null || !Double.isFinite(value)) continue;
			minimum = minimum == null ? value : Math.min(minimum, value);
		}
		return minimum;
	}
}
