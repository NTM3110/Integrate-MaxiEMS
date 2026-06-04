package io.openems.edge.controller.tools;

import java.util.ArrayDeque;
import java.util.Deque;

public class R0Estimator {
    /**
     * Online R0 estimator using sliding-window least-squares on deltas:
     *   R0 = - sum(dU*dI) / sum(dI^2), over the last W samples where |dI| > minDI
     * Then smoothed with EMA and rate-limited to avoid jitter.
     */
    private final int window;
    private final double minDI;
    private final double alpha;          // EMA alpha
    private double r0;                   // current smoothed estimate
    private final double r0Min;
    private final double r0Max;
    private final double rateLimitFrac;

    private final int maxLen;            // window + 1
    private final Deque<Double> uHist;
    private final Deque<Double> iHist;

    // ---- Constructors ----

    /** Defaults mirroring the Python version. */
    public R0Estimator() {
        this(30, 0.2, 0.03, 0.01, 1e-4, 0.5, 0.2);
    }

    public R0Estimator(int window,
                       double minDI,
                       double alphaEma,
                       double r0Init,
                       double r0Min,
                       double r0Max,
                       double rateLimitFrac) {
        if (window < 3) {
            throw new IllegalArgumentException("window must be >= 3");
        }
        this.window = window;
        this.minDI = minDI;
        this.alpha = alphaEma;
        this.r0 = r0Init;
        this.r0Min = r0Min;
        this.r0Max = r0Max;
        this.rateLimitFrac = rateLimitFrac;

        this.maxLen = window + 1;
        this.uHist = new ArrayDeque<>(this.maxLen);
        this.iHist = new ArrayDeque<>(this.maxLen);
    }

    // ---- Public API ----

    /**
     * Push one sample (U_k, I_k).
     * @return the current smoothed R0 estimate.
     */
    public double update(double U, double I) {
        // Append new samples, enforcing max length (acts like deque with maxlen)
        if (uHist.size() == maxLen) uHist.removeFirst();
        if (iHist.size() == maxLen) iHist.removeFirst();
        uHist.addLast(U);
        iHist.addLast(I);

        if (uHist.size() < maxLen) {
            // Not enough samples yet to form window of deltas
            return r0;
        }

        // Convert to arrays for indexed access while preserving order
        Double[] u = uHist.toArray(new Double[0]);
        Double[] i = iHist.toArray(new Double[0]);

        // Accumulate numerator and denominator over valid delta pairs
        double numer = 0.0;
        double denom = 0.0;
        int valid = 0;

        for (int k = 0; k < u.length - 1; k++) {
            double dU = u[k + 1] - u[k];
            double dI = i[k + 1] - i[k];
            if (Math.abs(dI) > minDI) {
                numer += dU * dI;
                denom += dI * dI;
                valid++;
            }
        }

        if (valid >= 3 && denom > 1e-12) {
            double r0New = -numer / denom;
            if (Double.isFinite(r0New) && r0New >= r0Min && r0New <= r0Max) {
                // EMA smoothing
                double r0Smooth = alpha * r0New + (1.0 - alpha) * r0;

                // Rate limit (± rateLimitFrac per step, relative to current r0 magnitude)
                double maxChange = Math.abs(r0) * rateLimitFrac;
                double r0Limited = clip(r0Smooth, r0 - maxChange, r0 + maxChange);

                // Hard bounds
                r0 = clip(r0Limited, r0Min, r0Max);
            }
        }

        return r0;
    }

    /** Returns the current estimate without updating. */
    public double getR0() {
        return r0;
    }

    // ---- Helpers ----

    private static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
