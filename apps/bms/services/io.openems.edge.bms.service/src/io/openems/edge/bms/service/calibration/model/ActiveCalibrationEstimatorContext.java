package io.openems.edge.bms.service.calibration.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable, non-authoritative estimator progress for one active calibration. */
public record ActiveCalibrationEstimatorContext(String stringKey, long calibrationEventId, long calibrationStageId,
		long calibrationProcessId, int processAttemptNumber, String eventType, String stageType, String processType,
		UUID sourceGeneration, long sourceSequence, long sourceMeasuredAt, long publishedAt,
		Map<Integer, CellProgress> cells) {

	public ActiveCalibrationEstimatorContext {
		cells = Map.copyOf(new LinkedHashMap<>(cells));
	}

	public boolean initialBaselineDischarge() {
		return "INITIAL_BASELINE".equals(this.eventType) && "DISCHARGE".equals(this.stageType)
				&& "CONTROLLED_DISCHARGE".equals(this.processType);
	}

	/** True when this publication supersedes {@code other} for the same string. */
	public boolean newerThan(ActiveCalibrationEstimatorContext other) {
		if (other == null) return true;
		if (this.calibrationProcessId != other.calibrationProcessId) {
			return this.calibrationProcessId > other.calibrationProcessId;
		}
		if (this.processAttemptNumber != other.processAttemptNumber) {
			return this.processAttemptNumber > other.processAttemptNumber;
		}
		if (this.sourceGeneration != null && this.sourceGeneration.equals(other.sourceGeneration)) {
			return this.sourceSequence >= other.sourceSequence;
		}
		return this.publishedAt >= other.publishedAt;
	}

	public record CellProgress(int cellIndex, boolean cutoffReached, Double cutoffCapacityAh) {
		public CellProgress {
			if (cellIndex < 1) throw new IllegalArgumentException("cellIndex must be positive");
			if (cutoffCapacityAh != null && (!Double.isFinite(cutoffCapacityAh) || cutoffCapacityAh <= 0)) {
				throw new IllegalArgumentException("cutoffCapacityAh must be finite and positive");
			}
			if (!cutoffReached && cutoffCapacityAh != null) {
				throw new IllegalArgumentException("A non-crossed cell cannot have cutoff capacity");
			}
		}
	}
}
