package io.openems.edge.pvinverter.api;

/** Shared, scale-safe calculations for RTS PV inverter drivers. */
public final class RtsPvInverterUtils {

	public enum CalculationStatus {
		VALID, UNAVAILABLE, INVALID
	}

	public static record CalculationResult<T>(T value, CalculationStatus status) {
		public boolean isValid() {
			return this.status == CalculationStatus.VALID;
		}

		public boolean isInvalid() {
			return this.status == CalculationStatus.INVALID;
		}
	}

	private RtsPvInverterUtils() {
	}

	/** Calculates W from mV and mA. */
	public static CalculationResult<Integer> calculateDcPower(Integer voltageMilliVolt, Integer currentMilliAmpere) {
		if (voltageMilliVolt == null || currentMilliAmpere == null) {
			return unavailable();
		}
		if (voltageMilliVolt < 0 || currentMilliAmpere < 0) {
			return invalid();
		}
		var power = Math.round((double) voltageMilliVolt * currentMilliAmpere / 1_000_000D);
		if (power > Integer.MAX_VALUE) {
			return invalid();
		}
		return valid((int) power);
	}

	/**
	 * Calculates power factor in thousandths. A negative reactive power produces a
	 * negative power factor; positive or zero reactive power produces a positive
	 * power factor.
	 */
	public static CalculationResult<Integer> calculatePowerFactor(Integer activePower, Integer reactivePower,
			Integer apparentPower) {
		if (activePower == null || reactivePower == null || apparentPower == null) {
			return unavailable();
		}
		if (apparentPower < 0 || Math.abs((long) activePower) > apparentPower) {
			return invalid();
		}
		if (apparentPower == 0) {
			return activePower == 0 ? unavailable() : invalid();
		}
		var magnitude = (int) Math.round(Math.abs((double) activePower) * 1000D / apparentPower);
		magnitude = Math.min(1000, magnitude);
		return valid(reactivePower < 0 ? -magnitude : magnitude);
	}

	/** Sums all available values, returning unavailable when no value is present. */
	public static CalculationResult<Integer> sumAvailable(Integer... values) {
		long sum = 0L;
		var available = false;
		for (var value : values) {
			if (value == null) {
				continue;
			}
			available = true;
			sum += value;
			if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) {
				return invalid();
			}
		}
		return available ? valid((int) sum) : unavailable();
	}

	/** Averages all available values, returning unavailable when none is present. */
	public static CalculationResult<Integer> averageAvailable(Integer... values) {
		long sum = 0L;
		var count = 0;
		for (var value : values) {
			if (value != null) {
				sum += value;
				count++;
			}
		}
		if (count == 0) {
			return unavailable();
		}
		var average = Math.round((double) sum / count);
		if (average < Integer.MIN_VALUE || average > Integer.MAX_VALUE) {
			return invalid();
		}
		return valid((int) average);
	}

	/** Scales a raw integer and rejects sentinels and integer overflow. */
	public static CalculationResult<Integer> scaleToInteger(Long rawValue, long multiplier, long divisor,
			long... sentinels) {
		if (divisor == 0) {
			throw new IllegalArgumentException("Divisor must not be zero");
		}
		if (rawValue == null) {
			return unavailable();
		}
		for (var sentinel : sentinels) {
			if (rawValue == sentinel) {
				return invalid();
			}
		}
		try {
			var scaled = Math.multiplyExact(rawValue, multiplier) / divisor;
			if (scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE) {
				return invalid();
			}
			return valid((int) scaled);
		} catch (ArithmeticException e) {
			return invalid();
		}
	}

	private static <T> CalculationResult<T> valid(T value) {
		return new CalculationResult<>(value, CalculationStatus.VALID);
	}

	private static <T> CalculationResult<T> unavailable() {
		return new CalculationResult<>(null, CalculationStatus.UNAVAILABLE);
	}

	private static <T> CalculationResult<T> invalid() {
		return new CalculationResult<>(null, CalculationStatus.INVALID);
	}
}
