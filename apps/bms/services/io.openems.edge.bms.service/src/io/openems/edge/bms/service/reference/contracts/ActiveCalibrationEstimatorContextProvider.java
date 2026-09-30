package io.openems.edge.bms.service.reference.contracts;

import java.util.Optional;

import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;

/** Supplies immutable active-calibration progress without blocking a controller cycle. */
public interface ActiveCalibrationEstimatorContextProvider {

	Optional<ActiveCalibrationEstimatorContext> activeByStringKey(String stringKey);
}
