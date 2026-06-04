package io.openems.edge.controller.tools;

import java.util.Arrays;

public class TemperatureFactor {
	private static final double[] T = {
	        5, 10, 15, 16, 17, 18, 19, 20, 21,
	        22, 23, 24, 25, 26, 27, 28, 29,
	        30, 31, 32, 33, 34, 35, 40, 45
	    };

	    private static final double[] K = {
	        1.289, 1.190, 1.119, 1.110, 1.094, 1.083, 1.070, 1.056, 1.042,
	        1.031, 1.021, 1.010, 1.000, 0.988, 0.979, 0.971, 0.963,
	        0.956, 0.949, 0.941, 0.937, 0.934, 0.930, 0.894, 0.874
	    };

	    private TemperatureFactor() {}

	    /** Returns the factor for the given temperature (°C). */
	    public static double getFactor(double tempC) {
	        int idx = Arrays.binarySearch(T, tempC);
	        if (idx >= 0) {
	            return K[idx]; // exact match
	        }

	        int ins = -idx - 1; // insertion point
	        if (ins == 0) {
	            // below range: extrapolate from first segment
	            return lerp(T[0], K[0], T[1], K[1], tempC);
	        } else if (ins == T.length) {
	            // above range: extrapolate from last segment
	            int n = T.length;
	            return lerp(T[n - 2], K[n - 2], T[n - 1], K[n - 1], tempC);
	        } else {
	            // between two known points: interpolate
	            return lerp(T[ins - 1], K[ins - 1], T[ins], K[ins], tempC);
	        }
	    }

	    private static double lerp(double x0, double y0, double x1, double y1, double x) {
	        return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
	    }

	    // Optional: expose copies of the raw table
	    public static double[] temperatures() { return Arrays.copyOf(T, T.length); }
	    public static double[] factors()      { return Arrays.copyOf(K, K.length); }
}
