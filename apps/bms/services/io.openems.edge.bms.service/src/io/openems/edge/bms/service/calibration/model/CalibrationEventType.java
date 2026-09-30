package io.openems.edge.bms.service.calibration.model;

/** Supported operator-created calibration processes. */
public enum CalibrationEventType {
	INITIAL_BASELINE,
	MONTHLY_INSPECTION,
	SIX_MONTH_INSPECTION,
	PERIODIC_DISCHARGE
}
