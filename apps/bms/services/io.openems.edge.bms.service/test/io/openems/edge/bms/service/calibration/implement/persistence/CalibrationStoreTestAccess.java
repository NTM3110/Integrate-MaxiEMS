package io.openems.edge.bms.service.calibration.implement.persistence;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidenceCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Process;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.Stage;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.WorkerEvent;

/** Test-only access to persistence policy seams; production repository stays private. */
public final class CalibrationStoreTestAccess {
	private CalibrationStoreTestAccess() { }
	public static void validateProcessStartPolicy(Stage stage, Process process) {
		CalibrationEventStore.validateProcessStartPolicy(stage, process);
	}
	public static void validateManualProcessCompletion(Process process) {
		CalibrationEventStore.validateManualProcessCompletion(process);
	}
	public static void validateInitialBaselineReviewEvidence(EventDetail detail, Stage stage, ManualEvidenceCommand command) {
		CalibrationEventStore.validateInitialBaselineReviewEvidence(detail, stage, command);
	}
	public static void validateActiveProcessDefinitions(EventDetail detail) {
		CalibrationEventStore.validateActiveProcessDefinitions(detail);
	}
	public static void validateRequiredProcessProperties(CalibrationEventConfiguration configuration, Process process) {
		CalibrationEventStore.validateRequiredProcessProperties(configuration, process);
	}
	public static CalibrationEventConfiguration configuration(String json) {
		return CalibrationEventStore.configuration(json);
	}
	public static boolean allowsAutomaticFinalSnapshotCompletion(WorkerEvent event) {
		return CalibrationEventStore.allowsAutomaticFinalSnapshotCompletion(event);
	}
	public static Long latestEvaluatedCurrentMeasuredAt(String checkpoint, Long value) {
		return CalibrationEventStore.latestEvaluatedCurrentMeasuredAt(checkpoint, value);
	}
}
