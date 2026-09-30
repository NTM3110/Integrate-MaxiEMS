package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;

/**
 * Decides whether an automatic evaluation merits a durable sample.  The worker
 * still evaluates every calculator snapshot; this policy only bounds the
 * expensive sample plus per-cell audit write.
 */
public final class CalibrationSamplePersistencePolicy {

	private CalibrationSamplePersistencePolicy() {
	}

	public static List<PersistenceReason> reasonsToPersist(
			CalibrationStageEvaluation<?> evaluation, long evaluatedAtMillis, Long lastPersistedAtMillis,
			boolean qualityChanged, CalibrationProcessDefinition.SamplingPolicy samplingPolicy,
			int persistenceIntervalSeconds) {
		if (samplingPolicy == CalibrationProcessDefinition.SamplingPolicy.NONE) return List.of();
		if (samplingPolicy == CalibrationProcessDefinition.SamplingPolicy.FINAL_SNAPSHOT) {
			return evaluation.persistenceReasons().contains(PersistenceReason.FINAL_SNAPSHOT)
					? List.of(PersistenceReason.FINAL_SNAPSHOT) : List.of();
		}
		if (evaluation.persistenceReasons().isEmpty()) return List.of();
		boolean periodicDue = lastPersistedAtMillis == null
				|| evaluatedAtMillis - lastPersistedAtMillis >= persistenceIntervalSeconds * 1_000L;
		var reasons = new ArrayList<PersistenceReason>();
		for (var reason : evaluation.persistenceReasons()) {
			boolean include = switch (reason) {
			case PERIODIC_SAMPLE -> periodicDue;
			// QUALITY_CHANGE is edge-triggered. A monitor may report the same blocker
			// on every calculator snapshot, but only the signature transition is audit.
			case QUALITY_CHANGE -> qualityChanged;
			default -> true;
			};
			if (include && !reasons.contains(reason)) reasons.add(reason);
		}
		// A healthy reading generally carries PERIODIC_SAMPLE only. Persist it
		// immediately when it clears or changes the prior blocker signature.
		if (qualityChanged && evaluation.persistenceReasons().contains(PersistenceReason.PERIODIC_SAMPLE)
				&& !reasons.contains(PersistenceReason.QUALITY_CHANGE)) {
			reasons.add(PersistenceReason.QUALITY_CHANGE);
		}
		return List.copyOf(reasons);
	}
}
