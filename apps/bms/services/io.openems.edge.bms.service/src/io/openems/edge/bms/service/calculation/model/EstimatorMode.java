package io.openems.edge.bms.service.calculation.model;

import java.util.Locale;

/** Rollout mode for the BMS calculator estimator. */
public enum EstimatorMode {
	AUTO,
	LEGACY,
	BASELINE_V1;

	public static EstimatorMode parse(String value) {
		if (value == null || value.isBlank()) return AUTO;
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("estimatorMode must be AUTO, LEGACY or BASELINE_V1", e);
		}
	}

	/** AUTO and the compatibility alias use the same reference-aware path. */
	public boolean baselineAware() {
		return this != LEGACY;
	}
}
