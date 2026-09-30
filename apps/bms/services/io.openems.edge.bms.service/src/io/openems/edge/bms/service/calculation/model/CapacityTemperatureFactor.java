package io.openems.edge.bms.service.calculation.model;

import java.util.Arrays;

/**
 * The single calculation-owned Kc source: Table 6 of the VRLA lifecycle guide.
 * Values are interpolated within the documented range and never extrapolated.
 */
public final class CapacityTemperatureFactor {
	private static final double[] TEMPERATURES = { 5, 10, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
			27, 28, 29, 30, 31, 32, 33, 34, 35, 40, 45 };
	private static final double[] FACTORS = { 1.289, 1.190, 1.119, 1.110, 1.094, 1.083, 1.070, 1.056, 1.042,
			1.031, 1.021, 1.010, 1.000, 0.988, 0.979, 0.971, 0.963, 0.956, 0.949, 0.941, 0.937, 0.934,
			0.930, 0.894, 0.874 };

	private CapacityTemperatureFactor() {
	}

	/**
	 * Gets the interpolated Kc for a qualified representative discharge
	 * temperature.
	 *
	 * @param temperatureC the representative discharge temperature in degrees C
	 * @return the factor, or {@code null} outside the documented table range
	 */
	public static Double factor(double temperatureC) {
		if (!Double.isFinite(temperatureC) || temperatureC < TEMPERATURES[0]
				|| temperatureC > TEMPERATURES[TEMPERATURES.length - 1]) {
			return null;
		}
		int index = Arrays.binarySearch(TEMPERATURES, temperatureC);
		if (index >= 0) {
			return FACTORS[index];
		}
		int upper = -index - 1;
		int lower = upper - 1;
		return FACTORS[lower] + (FACTORS[upper] - FACTORS[lower])
				* (temperatureC - TEMPERATURES[lower]) / (TEMPERATURES[upper] - TEMPERATURES[lower]);
	}
}
