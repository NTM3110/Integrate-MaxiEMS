package io.openems.edge.bms.controllers.calculation.estimator;

/** Immutable per-cell SoH result with provenance. */
public record SohResult(Double valuePct, Method method, Quality quality, String note,
		Long calibrationEventId, Long calibrationProcessId) {

	public enum Method {
		RESISTANCE_ESTIMATE,
		IN_PROGRESS,
		IN_PROGRESS_CAPACITY,
		AUTHORITATIVE_BASELINE,
		UNAVAILABLE
	}

	public enum Quality {
		ESTIMATED,
		PROVISIONAL,
		AUTHORITATIVE,
		UNAVAILABLE
	}
}
