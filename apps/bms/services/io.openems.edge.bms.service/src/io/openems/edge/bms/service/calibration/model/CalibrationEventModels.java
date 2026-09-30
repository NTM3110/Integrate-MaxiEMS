package io.openems.edge.bms.service.calibration.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonElement;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;

/** Typed persistence and future JSON-facade contracts for calibration events. */
public final class CalibrationEventModels {

	private CalibrationEventModels() {
	}

	public enum EventStatus {
		DRAFT, ACTIVE, BLOCKED, REVALIDATION_REQUIRED, COMPLETED, ABORTED, CANCELLED
	}

	public enum StageState {
		NOT_STARTED, READY, ACTIVE, BLOCKED, REVALIDATION_REQUIRED, COMPLETED, SKIPPED, FAILED
	}

	public enum ProcessState {
		NOT_STARTED, ACTIVE, BLOCKED, COMPLETED, FAILED
	}

	public enum ActionCode {
		COMPLETE_STAGE, COMPLETE_WITH_GAPS, REVALIDATE_STAGE, SKIP_STAGE, FAIL_STAGE, ABORT_EVENT
	}

	public enum ActionIntent {
		PRIMARY, WARNING, DANGER
	}

	public record AllowedAction(ActionCode code, String label, ActionIntent intent, boolean enabled,
			String disabledReason, boolean requiresReason, boolean requiresAcknowledgement,
			List<String> acknowledgementItems) {
		public AllowedAction {
			acknowledgementItems = List.copyOf(acknowledgementItems);
		}
	}

	public record EventSummary(long id, UUID stringId, CalibrationEventType eventType, EventStatus status,
			Long currentStageId, long eventVersion, long measurementRevision, UUID createdBy, String createdByName,
			Instant createdAt, Instant closedAt, Instant baselineApprovedAt, UUID baselineApprovedBy,
			String baselineApprovedByName) {
	}

	public record Stage(long id, long calibrationEventId, int order, CalibrationStageType type, StageState state,
			JsonElement properties, String result, String blockerCode, JsonElement blockerDetail, Instant startedAt,
			Instant completedAt, UUID completedBy, String acceptedAction, String acceptedReason,
			String acceptedActor, Instant acceptedAt, List<String> acknowledgedGaps) {
	}

	public record Process(long id, long calibrationEventId, long calibrationStageId, int order, String type,
			int attemptNumber, ProcessState state, String samplingPolicy, JsonElement configuration,
			JsonElement runtime, JsonElement dischargeRuntime, JsonElement properties, Instant startedAt,
			Instant completedAt) {
		public Process {
			// Gson trees are mutable. Keep the authoritative fields and deprecated
			// compatibility projection independent even when a caller reuses one tree.
			configuration = copy(configuration);
			runtime = copy(runtime);
			dischargeRuntime = copy(dischargeRuntime);
			properties = copy(properties);
		}

		/** Compatibility constructor for policy/unit fixtures that have no persisted runtime. */
		public Process(long id, long calibrationEventId, long calibrationStageId, int order, String type,
				int attemptNumber, ProcessState state, String samplingPolicy, JsonElement configuration,
				Instant startedAt, Instant completedAt) {
			this(id, calibrationEventId, calibrationStageId, order, type, attemptNumber, state, samplingPolicy,
					configuration, null, null, configuration, startedAt, completedAt);
		}

		/** One-release legacy alias; new callers use the three separated accessors. */
		public JsonElement properties() {
			return this.properties;
		}

		private static JsonElement copy(JsonElement value) {
			return value == null ? null : value.deepCopy();
		}
	}

	public record EventDetail(EventSummary event, CalibrationEventConfiguration configuration,
			Long referenceCalibrationEventId, List<String> blockers,
			List<String> missingEvidence, List<Stage> stages, List<Process> processes,
			ReferenceCurve referenceCurve, List<AllowedAction> allowedActions) {
		public EventDetail {
			blockers = List.copyOf(blockers);
			missingEvidence = List.copyOf(missingEvidence);
			stages = List.copyOf(stages);
			processes = List.copyOf(processes);
			allowedActions = List.copyOf(allowedActions);
		}

		public EventDetail(EventSummary event, CalibrationEventConfiguration configuration,
				Long referenceCalibrationEventId, List<String> blockers, List<String> missingEvidence,
				List<Stage> stages, List<Process> processes, List<AllowedAction> allowedActions) {
			this(event, configuration, referenceCalibrationEventId, blockers, missingEvidence, stages, processes,
					null, allowedActions);
		}
	}

	/** Published curve produced by this event, independently of whether it is still active. */
	public record ReferenceCurve(long curveVersionId, long calibrationEventId, UUID stringId,
			long calibrationStageId, long calibrationProcessId, int processAttemptNumber,
			int curveSchemaVersion, double capacityReferenceAh, double stringSafeUsableCapacityAh,
			double allCellsEndpointAh, int cellCount, int pointCount, String contentHash,
			Instant publishedAt, boolean activeBaseline) {
	}

	public record CurvePoint(long curveVersionId, int cellIndex, int pointOrder, long calibrationSampleId,
			double milestonePercent, double normalizedActualDodPercent, double dischargedAh,
			double cellVoltageV, Double cellTemperatureC, Integer cellResistanceUohm, Double cellSocPct,
			Double cellSohPct, Double stringVoltageV, Double averageVoltageV, String quality, boolean usable) {
	}

	public record CurvePointPage(ReferenceCurve referenceCurve, int offset, int limit, long total,
			List<CurvePoint> points) {
		public CurvePointPage {
			points = List.copyOf(points);
		}
	}

	public record StartProcessCommand(long calibrationEventId, long calibrationStageId, long calibrationProcessId,
			long expectedEventVersion) {
	}

	public record CompleteProcessCommand(long calibrationEventId, long calibrationStageId, long calibrationProcessId,
			long expectedEventVersion, String reason) {
	}

	/** Confirms that the operator physically stopped a safety-latched discharge. */
	public record ConfirmDischargeStoppedCommand(long calibrationEventId, long calibrationStageId,
			long calibrationProcessId, long expectedEventVersion) {
	}

	public record CreateCommand(UUID stringId, CalibrationEventConfiguration configuration,
			Long referenceCalibrationEventId, AuthenticatedPrincipal actor) {
	}

	/** Versioned command to permanently remove an unstarted draft aggregate. */
	public record DeleteDraftCommand(long calibrationEventId, long expectedEventVersion) {
	}

	/** Minimal success projection after a draft aggregate has been removed. */
	public record DeletedEvent(long calibrationEventId) {
	}

	public record ExecuteActionCommand(long calibrationEventId, long calibrationStageId, ActionCode actionCode,
			long expectedEventVersion, String reason, List<String> acknowledgedEvidence) {
		public ExecuteActionCommand {
			acknowledgedEvidence = acknowledgedEvidence == null ? List.of() : List.copyOf(acknowledgedEvidence);
		}
	}

	public record RedoStageCommand(long calibrationEventId, long calibrationStageId, long expectedEventVersion,
			String reason) {
	}

	/** Versioned, Prerequisites-only setting for an Initial Baseline Modbus demo. */
	public record ConfigureDemoTimeScaleCommand(long calibrationEventId, long calibrationStageId,
			long expectedEventVersion, int demoTimeScaleFactor) {
	}

	/** Versioned safety floor selected before controlled discharge starts. */
	public record ConfigureDischargeSafetyFloorCommand(long calibrationEventId, long calibrationStageId,
			long expectedEventVersion, double safetyFloorVoltagePerCell) {
	}

	/** Versioned discharge-monitor policy selected before controlled discharge starts. */
	public record ConfigureDischargeMonitorCommand(long calibrationEventId, long calibrationStageId,
			long expectedEventVersion, double safetyFloorVoltagePerCell,
			int maxWrongCurrentDirectionSamples) {
	}

	/**
	 * Versioned Float-window policy selected before Initial Baseline charge starts.
	 * The equipment configuration remains immutable; this policy is persisted on
	 * the affected charge processes so the worker can audit the exact policy used.
	 */
	public record ConfigureFloatStablePolicyCommand(long calibrationEventId, long calibrationStageId,
			long expectedEventVersion, int floatStableDurationMinutes, double floatVoltageToleranceV,
			double floatCurrentMinA, double floatCurrentMaxA) {
	}

	/** Versioned policy update for the exact currently-active Float process. */
	public record ConfigureActiveFloatCurrentRangeCommand(long calibrationEventId, long calibrationStageId,
			long calibrationProcessId, int processAttemptNumber, long expectedEventVersion,
			double floatCurrentMinA, double floatCurrentMaxA, String reason) {
	}

	/** Versioned policy update for the exact currently-active Voc capture process. */
	public record ConfigureActiveVocDeadbandCommand(long calibrationEventId, long calibrationStageId,
			long calibrationProcessId, int processAttemptNumber, long expectedEventVersion,
			double vocCurrentDeadbandA, String reason) {
	}

	public record ManualEvidenceCommand(long calibrationEventId, long calibrationStageId, long expectedEventVersion,
			String evidenceType, String status, Double numericValue, String textValue, String unit,
			String instrumentId, Double instrumentResolution, Double instrumentAccuracy,
			String calibrationReference, String note, String externalReference, Long calibrationSampleId) {
		public ManualEvidenceCommand(long calibrationEventId, long calibrationStageId, long expectedEventVersion,
				String evidenceType, String status, Double numericValue, String textValue, String unit,
				String instrumentId, Double instrumentResolution, Double instrumentAccuracy,
				String calibrationReference, String note, String externalReference) {
			this(calibrationEventId, calibrationStageId, expectedEventVersion, evidenceType, status, numericValue,
					textValue, unit, instrumentId, instrumentResolution, instrumentAccuracy, calibrationReference,
					note, externalReference, null);
		}
	}

	/** Append-only audit and report projections loaded with event detail. */
	public record EventHistory(List<Transition> transitions, List<ManualEvidence> evidence, Report report) {
		public EventHistory {
			transitions = List.copyOf(transitions);
			evidence = List.copyOf(evidence);
		}
	}

	public record Transition(long id, long calibrationEventId, long calibrationStageId, String fromState,
			String toState, String action, String reason, UUID actorId, String actorName, JsonElement evidence,
			long resultingEventVersion, int transitionOrder, Instant occurredAt) {
	}

	public record ManualEvidence(long id, long calibrationEventId, long calibrationStageId, String evidenceType,
			String status, Double numericValue, String textValue, String unit, String instrumentId,
			Double instrumentResolution, Double instrumentAccuracy, String calibrationReference, UUID actorId,
			String actorName, String note, String externalReference, Long calibrationSampleId, Instant recordedAt) {
	}

	public record Report(int reportSchemaVersion, JsonElement data, Instant generatedAt, UUID generatedBy,
			String contentHash) {
	}

	public record Sample(long id, long calibrationEventId, long calibrationStageId, Long calibrationProcessId,
			Integer processAttemptNumber,
			UUID snapshotGeneration,
			long snapshotSequence, Instant measurementTime, String persistenceReason, String quality,
			String blockerReason, Double rawCurrentA, Double domainCurrentA, String currentDirection,
			Long mappingVersion, Instant currentMeasuredAt, String currentQuality, Double stringVoltageV,
			Double midpointVoltageV, Double averageVoltageV, Double ambientTemperatureC, Double averageCellTemperatureC,
			Double deltaSeconds, Double deltaAh, Double rawUsedAh, Double representativeDischargeTemperatureC,
			Double temperatureFactorKc,
			Double correctedUsedAh, Double socPct, String socMethod, Double dodPct, Double floatTargetVoltageV,
			Double boostTargetVoltageV, Instant createdAt, List<CellSample> cells) {
		public Sample {
			cells = List.copyOf(cells);
		}
	}

	public record CellSample(int cellIndex, Double voltageV, Double temperatureC, Integer resistanceUohm,
			Double socPct, String socMethod, Double sohPct, String sohMethod, String quality,
			String availabilityWarning) {
	}

	public record WorkerEvent(long eventId, long stageId, UUID stringId, String stringKey,
			CalibrationEventConfiguration configuration, int demoTimeScaleFactor, CalibrationStageType stageType, StageState stageState,
			JsonElement checkpoint, Long previousMonthlyEventId, Double previousMonthlyFloatCurrentA,
			Long previousMonthlyMeasuredAt, Long processId, Integer processAttemptNumber, String processType,
			ProcessState processState, String samplingPolicy,
			JsonElement processProperties, JsonElement runtimeCheckpoint) {
		public WorkerEvent(long eventId, long stageId, UUID stringId, String stringKey,
				CalibrationEventConfiguration configuration, int demoTimeScaleFactor, CalibrationStageType stageType,
				StageState stageState, JsonElement checkpoint, Long previousMonthlyEventId,
				Double previousMonthlyFloatCurrentA, Long previousMonthlyMeasuredAt, Long processId,
				Integer processAttemptNumber, ProcessState processState, String samplingPolicy,
				JsonElement processProperties) {
			this(eventId, stageId, stringId, stringKey, configuration, demoTimeScaleFactor, stageType, stageState,
					checkpoint, previousMonthlyEventId, previousMonthlyFloatCurrentA, previousMonthlyMeasuredAt,
					processId, processAttemptNumber, null, processState, samplingPolicy, processProperties, null);
		}
	}

	public record SampleWrite(CalibrationMeasurement measurement, CalibrationStageEvaluation<?> evaluation,
			String quality, String blockerReason, long evaluatedAtMillis) {
	}
}
