package io.openems.edge.bms.service.monitoring.contracts;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

/** Public read/acknowledgement boundary for durable monitoring alarms. */
public interface MonitoringAlarmService {

	enum AcknowledgeResult {
		ACKNOWLEDGED_ACTIVE, ACKNOWLEDGED_AND_CLEARED, ALREADY_ACKNOWLEDGED, NOT_ACTIVE, NOT_FOUND
	}

	record AlarmTransition(long id, String type, Instant transitionedAt, String actor, Double observedValue,
			Double thresholdValue, String reason, BmsMonitoringSnapshot.ReferenceIdentity reference,
			java.util.UUID sourceGeneration, long sourceSequence, Instant sourceCapturedAt, int policyVersion) {
	}

	record AlarmOccurrence(long id, String stringKey, BmsMonitoringSnapshot.Alarm alarm, String subjectKey,
			Integer cellIndex, String status, boolean conditionActive, Instant raisedAt, Instant conditionClearedAt,
			Instant clearedAt, Instant acknowledgedAt, String acknowledgedBy, Double triggerObservedValue,
			Double triggerThresholdValue, String triggerReason, java.util.UUID triggerGeneration,
			long triggerSequence, Instant triggerCapturedAt, BmsMonitoringSnapshot.ReferenceIdentity reference,
			ActiveCalibrationContext activeCalibration, int policyVersion, int raiseSamples, int clearSamples,
			double hysteresisValue, String hysteresisUnit, boolean latching, List<AlarmTransition> transitions) {
		public AlarmOccurrence {
			transitions = List.copyOf(transitions);
		}
	}

	/** Last publication committed by the alarm processor for one string. */
	record ProcessorCheckpoint(UUID publicationGeneration, Long publicationRevision, Instant processedAt) {
		public static ProcessorCheckpoint unavailable() {
			return new ProcessorCheckpoint(null, null, null);
		}
	}

	record AlarmListResult(List<AlarmOccurrence> alarms, ProcessorCheckpoint processorCheckpoint) {
		public AlarmListResult {
			alarms = List.copyOf(alarms);
		}
	}

	List<AlarmOccurrence> list(String stringKey, boolean includeCleared, int offset, int limit) throws SQLException;

	/** Read occurrences and the processing watermark from one database snapshot. */
	AlarmListResult listWithCheckpoint(String stringKey, boolean includeCleared, int offset, int limit)
			throws SQLException;

	AcknowledgeResult acknowledge(long occurrenceId, String actor, Instant acknowledgedAt) throws SQLException;
}
