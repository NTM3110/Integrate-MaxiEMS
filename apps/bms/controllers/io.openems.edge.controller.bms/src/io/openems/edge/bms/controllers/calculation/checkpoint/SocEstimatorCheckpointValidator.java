package io.openems.edge.bms.controllers.calculation.checkpoint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.openems.edge.bms.controllers.calculation.estimator.PerCellEstimatorState;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

/** Strict restore validation. Invalid persistence is ignored, never repaired. */
public final class SocEstimatorCheckpointValidator {

	private SocEstimatorCheckpointValidator() {
	}

	public static Optional<Map<Integer, PerCellEstimatorState>> restore(SocEstimatorCheckpoint checkpoint,
			String stringKey, int cellCount, String referenceIdentity, long currentMeasurementTimestamp,
			long maximumGapMillis) {
		if (checkpoint == null || checkpoint.schemaVersion() != SocEstimatorCheckpoint.CURRENT_SCHEMA_VERSION
				|| stringKey == null || !stringKey.equals(checkpoint.stringKey()) || cellCount <= 0
				|| checkpoint.cellCount() != cellCount || checkpoint.sourceGeneration() == null
				|| checkpoint.sourceSequence() < 0 || checkpoint.measurementTimestamp() <= 0
				|| referenceIdentity == null || referenceIdentity.isBlank() || checkpoint.reference() == null
				|| maximumGapMillis <= 0 || currentMeasurementTimestamp <= checkpoint.measurementTimestamp()
				|| currentMeasurementTimestamp - checkpoint.measurementTimestamp() > maximumGapMillis
				|| checkpoint.cells().size() != cellCount) {
			return Optional.empty();
		}
		var checkpointIdentity = checkpoint.reference().calibrationEventId() + ":"
				+ checkpoint.reference().curveVersionId() + ":" + checkpoint.reference().calibrationProcessId() + ":"
				+ checkpoint.reference().processAttemptNumber() + ":" + checkpoint.reference().contentHash();
		if (!referenceIdentity.equals(checkpointIdentity)) return Optional.empty();
		var restored = new LinkedHashMap<Integer, PerCellEstimatorState>(cellCount);
		for (var cellIndex = 1; cellIndex <= cellCount; cellIndex++) {
			var cell = checkpoint.cells().get(cellIndex);
			if (cell == null || !Double.isFinite(cell.socPct()) || cell.socPct() < 0 || cell.socPct() > 100
					|| cell.currentMeasuredAt() == null || cell.currentMeasuredAt() <= 0
					|| !referenceIdentity.equals(cell.referenceIdentity())
					|| cell.currentMeasuredAt() > checkpoint.measurementTimestamp()) {
				return Optional.empty();
			}
			restored.put(cellIndex,
					new PerCellEstimatorState(cell.socPct(), cell.currentMeasuredAt(), cell.referenceIdentity()));
		}
		return Optional.of(Map.copyOf(restored));
	}
}
