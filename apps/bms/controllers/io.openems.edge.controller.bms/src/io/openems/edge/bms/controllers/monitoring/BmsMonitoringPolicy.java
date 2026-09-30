package io.openems.edge.bms.controllers.monitoring;

/** Configurable alarm thresholds applied by the monitoring evaluator. */
public record BmsMonitoringPolicy(int snapshotFreshnessSeconds, double cellVoltageDeviationFraction,
		double batteryVoltageLowV, double batteryVoltageHighV, double resistanceBaselineMultiplier,
		double cellTemperatureAboveAverageC, double cellTemperatureAboveAmbientC,
		double floatCurrentBaselineMultiplier, double chargeOvercurrentCapacityFraction,
		double lowSocPct, double highAmbientTemperatureC, double midpointImbalanceV,
		double currentDeadbandA, double floatVoltageToleranceV, int modeStableSeconds) {

	public static BmsMonitoringPolicy defaults() {
		return new BmsMonitoringPolicy(60, 0.15, 200.0, 242.0, 1.25, 4.0, 6.0, 3.0, 0.20, 70.0, 40.0, 2.0,
				0.2, 1.0, 60);
	}
}
