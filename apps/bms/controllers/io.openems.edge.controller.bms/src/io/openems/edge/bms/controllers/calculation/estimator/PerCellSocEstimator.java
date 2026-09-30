package io.openems.edge.bms.controllers.calculation.estimator;

/** Pure actual-time coulomb-counting transition for one cell. */
public final class PerCellSocEstimator {

	private PerCellSocEstimator() {
	}

	public static SocResult update(PerCellEstimatorState previous, String referenceIdentity,
			Double measuredCellCapacityAh, Double domainCurrentA, Long currentMeasuredAt, boolean currentFresh,
			Double voltageFallbackPct, long maximumGapMillis) {
		return update(previous, referenceIdentity, measuredCellCapacityAh, domainCurrentA, currentMeasuredAt,
				currentFresh, voltageFallbackPct, false, maximumGapMillis);
	}

	public static SocResult update(PerCellEstimatorState previous, String referenceIdentity,
			Double measuredCellCapacityAh, Double domainCurrentA, Long currentMeasuredAt, boolean currentFresh,
			Double voltageFallbackPct, boolean stableFloat, long maximumGapMillis) {
		return update(previous, referenceIdentity, measuredCellCapacityAh, domainCurrentA, currentMeasuredAt,
				currentFresh, voltageFallbackPct, stableFloat, 1d, maximumGapMillis);
	}

	public static SocResult update(PerCellEstimatorState previous, String referenceIdentity,
			Double measuredCellCapacityAh, Double domainCurrentA, Long currentMeasuredAt, boolean currentFresh,
			Double voltageFallbackPct, boolean stableFloat, double timeScale, long maximumGapMillis) {
		var fallback = finitePercent(voltageFallbackPct);
		var capacityValid = measuredCellCapacityAh != null && Double.isFinite(measuredCellCapacityAh)
				&& measuredCellCapacityAh > 0;
		var hasReferenceIdentity = referenceIdentity != null && !referenceIdentity.isBlank();
		if (!hasReferenceIdentity && measuredCellCapacityAh == null) {
			return fallback(fallback, currentMeasuredAt, "NO_REFERENCE", "Initial Baseline capacity is unavailable");
		}
		if (!capacityValid || !hasReferenceIdentity) {
			return unavailable("Initial Baseline reference or cell capacity is invalid");
		}
		var currentUsable = currentFresh && domainCurrentA != null && Double.isFinite(domainCurrentA)
				&& currentMeasuredAt != null;
		if (stableFloat && currentUsable
				&& (previous == null || previous.currentMeasuredAt() == null
						|| currentMeasuredAt > previous.currentMeasuredAt())) {
			return new SocResult(100d, SocResult.Method.STABLE_FLOAT_REANCHOR, SocResult.Quality.VALID,
					"Re-anchored at a validated stable Float landmark",
					new PerCellEstimatorState(100d, currentMeasuredAt, referenceIdentity));
		}
		if (previous == null || !referenceIdentity.equals(previous.referenceIdentity())) {
			return fallback == null
					? unavailable("A valid voltage bootstrap is required for the Initial Baseline reference")
					: new SocResult(fallback, SocResult.Method.BASELINE_VOLTAGE_BOOTSTRAP,
							SocResult.Quality.ESTIMATED, "Bootstrapped from voltage for a new reference",
							new PerCellEstimatorState(fallback, currentUsable ? currentMeasuredAt : null,
									referenceIdentity));
		}
		if (!currentUsable) {
			return fallback == null
					? unavailable("Current is unavailable and voltage fallback is invalid", previous)
					: fallback(fallback, null, referenceIdentity, "Current unavailable; voltage fallback applied");
		}
		if (previous.currentMeasuredAt() == null) {
			return new SocResult(previous.socPct(), SocResult.Method.RETAINED, SocResult.Quality.RETAINED,
					"Current timestamp initialized; state retained",
					new PerCellEstimatorState(previous.socPct(), currentMeasuredAt, referenceIdentity));
		}
		long elapsedMillis = currentMeasuredAt - previous.currentMeasuredAt();
		if (elapsedMillis == 0) {
			return new SocResult(previous.socPct(), SocResult.Method.RETAINED, SocResult.Quality.RETAINED,
					"Duplicate current timestamp; state retained", previous);
		}
		if (elapsedMillis < 0 || maximumGapMillis <= 0 || elapsedMillis > maximumGapMillis
				|| !Double.isFinite(timeScale) || timeScale <= 0) {
			return fallback == null ? unavailable("Current interval is invalid and voltage fallback is unavailable")
					: fallback(fallback, currentMeasuredAt, referenceIdentity, "Invalid current interval; voltage fallback applied");
		}
		double elapsedSeconds = elapsedMillis / 1_000d * timeScale;
		double dischargedDeltaPct = domainCurrentA * elapsedSeconds / measuredCellCapacityAh / 3_600d * 100d;
		double soc = clamp(previous.socPct() - dischargedDeltaPct);
		return new SocResult(soc, SocResult.Method.BASELINE_COULOMB_COUNTING, SocResult.Quality.VALID,
				"Integrated domain current using measured cell capacity",
				new PerCellEstimatorState(soc, currentMeasuredAt, referenceIdentity));
	}

	private static SocResult fallback(Double value, Long measuredAt, String referenceIdentity, String note) {
		if (value == null) return unavailable(note);
		return new SocResult(value, SocResult.Method.VOLTAGE_FALLBACK, SocResult.Quality.ESTIMATED, note,
				new PerCellEstimatorState(value, measuredAt, referenceIdentity));
	}

	private static SocResult unavailable(String note) {
		return new SocResult(null, SocResult.Method.UNAVAILABLE, SocResult.Quality.UNAVAILABLE, note, null);
	}

	private static SocResult unavailable(String note, PerCellEstimatorState retainedState) {
		return new SocResult(null, SocResult.Method.UNAVAILABLE, SocResult.Quality.UNAVAILABLE, note, retainedState);
	}

	private static Double finitePercent(Double value) {
		return value != null && Double.isFinite(value) ? clamp(value) : null;
	}

	private static double clamp(double value) {
		return Math.max(0d, Math.min(100d, value));
	}
}
