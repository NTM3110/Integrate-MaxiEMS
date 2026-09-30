package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

/** Side-effect-free evaluator for one focused family of calibration stages. */
public interface CalibrationStageMonitor<C> {
	CalibrationStageEvaluation<C> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, C checkpoint, long nowMillis);
}
