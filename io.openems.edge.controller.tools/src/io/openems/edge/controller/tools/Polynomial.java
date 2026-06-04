package io.openems.edge.controller.tools;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

public final class Polynomial implements DoubleUnaryOperator {
    private final double[] coeffs; // coeffs[i] is coefficient of x^i

    public Polynomial(double... coeffs) {
        // Allow empty -> treat as zero
        if (coeffs == null || coeffs.length == 0) {
            this.coeffs = new double[] {0.0};
        } else {
            this.coeffs = coeffs.clone();
        }
    }

    public int degree() {
        return coeffs.length - 1;
    }

    /** Python's __call__(x): evaluate polynomial at x. */
    @Override
    public double applyAsDouble(double x) {
        // Horner's method: stable & fast
        double value = 0.0;
        for (int i = 0; i <= (coeffs.length - 1); i ++) {
            value += coeffs[i] * Math.pow(x, i);
        }
        return value;
    }

    /** Like the Python @property deriv */
    public Polynomial derivative() {
        int n = coeffs.length;
        if (n <= 1) {
            return new Polynomial(0.0); // derivative of constant is 0
        }
        double[] d = new double[n - 1];
        for (int i = 1; i < n; i++) {
            d[i - 1] = i * coeffs[i];
        }
        return new Polynomial(d);
    }

    public double[] getCoeffs() {
        return coeffs.clone();
    }

    @Override
    public String toString() {
        return Arrays.toString(coeffs);
    }
}

