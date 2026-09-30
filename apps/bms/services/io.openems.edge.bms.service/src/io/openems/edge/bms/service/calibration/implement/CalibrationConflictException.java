package io.openems.edge.bms.service.calibration.implement;

/** Indicates that a versioned operator command no longer matches persisted state. */
public final class CalibrationConflictException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	public CalibrationConflictException(String message) {
		super(message);
	}
}
