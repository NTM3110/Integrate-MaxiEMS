package io.openems.edge.bms.service.monitoring.model;

/** Immutable projection of the process policy that can affect live monitoring. */
public record ActiveCalibrationContext(Long calibrationEventId, Long calibrationStageId,
		Long calibrationProcessId, String eventType, String stageType, String processType,
		Double chargeCurrentLimitA) {

	public static ActiveCalibrationContext none() {
		return new ActiveCalibrationContext(null, null, null, null, null, null, null);
	}

	public boolean active() {
		return this.calibrationEventId != null;
	}
}
