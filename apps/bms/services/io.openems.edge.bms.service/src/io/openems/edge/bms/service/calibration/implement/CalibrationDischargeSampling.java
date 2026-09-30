package io.openems.edge.bms.service.calibration.implement;

/** Audited persistence cadence for Initial Baseline discharge samples. */
public final class CalibrationDischargeSampling {

	public static final String WALL_CLOCK_SECONDS_PROPERTY = "dischargeSamplingIntervalSeconds";
	public static final int PERIODIC_WALL_CLOCK_SECONDS = 30;
	public static final int DEMO_PERIODIC_WALL_CLOCK_SECONDS = 2;

	private CalibrationDischargeSampling() {
	}

	/** Returns the persistence cadence selected by the authoritative event scale. */
	public static int wallClockSeconds(int demoTimeScaleFactor) {
		CalibrationDemoTimeScale.requireSupported(demoTimeScaleFactor);
		return demoTimeScaleFactor == CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND
				? DEMO_PERIODIC_WALL_CLOCK_SECONDS : PERIODIC_WALL_CLOCK_SECONDS;
	}
}
