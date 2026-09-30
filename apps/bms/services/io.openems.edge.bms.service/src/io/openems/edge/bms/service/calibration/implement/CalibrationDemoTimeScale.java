package io.openems.edge.bms.service.calibration.implement;

/**
 * Event-owned simulation time policy for Initial Baseline Modbus demos.
 *
 * <p>
 * This factor deliberately scales only Float stable-window elapsed time and
 * Initial Baseline demo Discharge Ah integration. Snapshot freshness, source
 * timestamp ordering, transport-quality validation, charge duration and
 * cool-down remain in wall-clock time.
 */
public final class CalibrationDemoTimeScale {

	public static final String STAGE_PROPERTY = "demoTimeScaleFactor";
	public static final int REAL_TIME = 1;
	public static final int DEMO_MINUTE_PER_SECOND = 60;

	private CalibrationDemoTimeScale() {
	}

	public static int requireSupported(int value) {
		if (value == REAL_TIME || value == DEMO_MINUTE_PER_SECOND) return value;
		throw new IllegalArgumentException("demoTimeScaleFactor must be 1 or 60");
	}

	public static long scaledElapsedMillis(long wallClockElapsedMillis, int factor) {
		requireSupported(factor);
		if (wallClockElapsedMillis < 0) throw new IllegalArgumentException("elapsed time must not be negative");
		return Math.multiplyExact(wallClockElapsedMillis, factor);
	}
}
