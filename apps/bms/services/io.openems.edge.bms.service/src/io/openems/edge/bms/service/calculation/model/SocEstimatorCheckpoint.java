package io.openems.edge.bms.service.calculation.model;

import java.util.Map;
import java.util.UUID;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;

/** Immutable restart state for the baseline-aware per-cell SoC estimator. */
public record SocEstimatorCheckpoint(int schemaVersion, String stringKey, int cellCount, UUID sourceGeneration,
		long sourceSequence, long measurementTimestamp, EstimatorReferenceIdentity reference,
		Map<Integer, CellState> cells) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	public SocEstimatorCheckpoint {
		cells = cells == null ? Map.of() : Map.copyOf(cells);
	}

	/** Exact estimator state; invalid values are rejected by the validator. */
	public record CellState(double socPct, Long currentMeasuredAt, String referenceIdentity) {
	}
}
