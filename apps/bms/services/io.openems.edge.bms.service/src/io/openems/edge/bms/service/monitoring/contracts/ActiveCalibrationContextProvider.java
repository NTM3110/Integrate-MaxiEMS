package io.openems.edge.bms.service.monitoring.contracts;

import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;

/** Provides an in-memory calibration context; implementations must not block a cycle. */
public interface ActiveCalibrationContextProvider {

	ActiveCalibrationContext activeContext(String stringKey);
}
