package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.CalibrationConflictException;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;

import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventDetail;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ExecuteActionCommand;

/** Pure validation for optimistic versions and action metadata requirements. */
public final class CalibrationCommandValidator {

	private CalibrationCommandValidator() {
	}

	public static void requireVersion(EventDetail detail, long expectedVersion) {
		if (detail.event().eventVersion() != expectedVersion) {
			throw new CalibrationConflictException("Calibration event version changed; reload the event detail");
		}
	}

	public static void validatePayload(AllowedAction action, ExecuteActionCommand command) {
		if (action.requiresReason() && trimToNull(command.reason()) == null) {
			throw new IllegalArgumentException("A reason is required for " + action.code());
		}
		if (action.requiresAcknowledgement()
				&& !command.acknowledgedEvidence().containsAll(action.acknowledgementItems())) {
			throw new IllegalArgumentException("All evidence gaps must be acknowledged");
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
