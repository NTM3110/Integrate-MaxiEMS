package io.openems.edge.bms.service.calibration.model;

/** Ordered stage types used by calibration processes. */
public enum CalibrationStageType {
	PREREQUISITES,
	INITIAL_CHARGE,
	PRE_DISCHARGE_FLOAT,
	WAITING_FOR_LOAD,
	DISCHARGE,
	LOAD_DISCONNECT,
	RECHARGE,
	POST_RECHARGE_FLOAT,
	BASELINE_CAPTURE,
	MONTHLY_INSPECTION,
	SIX_MONTH_INSPECTION,
	COMPLETION
}
