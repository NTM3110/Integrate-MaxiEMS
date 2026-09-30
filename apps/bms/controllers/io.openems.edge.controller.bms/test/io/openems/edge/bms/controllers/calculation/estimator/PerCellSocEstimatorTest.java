package io.openems.edge.bms.controllers.calculation.estimator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PerCellSocEstimatorTest {

	private static final String REFERENCE = "42:2:300:1:hash";

	@Test
	public void appliesConfiguredTimeScaleOnlyToCoulombCounting() {
		var state = new PerCellEstimatorState(100d, 1_000L, REFERENCE);

		var production = PerCellSocEstimator.update(state, REFERENCE, 200d, 20d,
				2_000L, true, 90d, false, 1d, 10_000L);
		var demoX60 = PerCellSocEstimator.update(state, REFERENCE, 200d, 20d,
				2_000L, true, 90d, false, 60d, 10_000L);

		assertEquals(99.997222d, production.valuePct(), 0.000001d);
		assertEquals(99.833333d, demoX60.valuePct(), 0.000001d);
		assertEquals(Long.valueOf(2_000L), demoX60.state().currentMeasuredAt());
	}

	@Test
	public void integratesDomainCurrentUsingActualElapsedTimeAndPreciseCapacity() {
		var bootstrap = PerCellSocEstimator.update(null, REFERENCE, 200d, 20d, 1_000L, true, 80d, 10_000_000L);
		var result = PerCellSocEstimator.update(bootstrap.state(), REFERENCE, 200d, 20d,
				3_601_000L, true, 79d, 10_000_000L);

		assertEquals(Double.valueOf(70d), result.valuePct());
		assertEquals(SocResult.Method.BASELINE_COULOMB_COUNTING, result.method());
		assertEquals(SocResult.Quality.VALID, result.quality());
	}

	@Test
	public void signedChargeRaisesSocAndAllValidResultsClampToBounds() {
		var state = new PerCellEstimatorState(95d, 1_000L, REFERENCE);
		var overcharge = PerCellSocEstimator.update(state, REFERENCE, 100d, -100d,
				3_601_000L, true, 90d, 10_000_000L);
		assertEquals(Double.valueOf(100d), overcharge.valuePct());

		var stayAtHundred = PerCellSocEstimator.update(overcharge.state(), REFERENCE, 100d, -100d,
				7_201_000L, true, 90d, 10_000_000L);
		assertEquals(Double.valueOf(100d), stayAtHundred.valuePct());

		var empty = new PerCellEstimatorState(5d, 1_000L, REFERENCE);
		var overdischarge = PerCellSocEstimator.update(empty, REFERENCE, 100d, 100d,
				3_601_000L, true, 10d, 10_000_000L);
		assertEquals(Double.valueOf(0d), overdischarge.valuePct());

		var stayAtZero = PerCellSocEstimator.update(overdischarge.state(), REFERENCE, 100d, 100d,
				7_201_000L, true, 10d, 10_000_000L);
		assertEquals(Double.valueOf(0d), stayAtZero.valuePct());
	}

	@Test
	public void rejectsReplayGapAndInvalidInputsWithoutCoercingToZero() {
		var state = new PerCellEstimatorState(72d, 2_000L, REFERENCE);
		var replay = PerCellSocEstimator.update(state, REFERENCE, 180d, 20d, 2_000L, true, 60d, 10_000L);
		assertEquals(Double.valueOf(72d), replay.valuePct());
		assertEquals(SocResult.Method.RETAINED, replay.method());

		var gap = PerCellSocEstimator.update(state, REFERENCE, 180d, 20d, 20_000L, true, 61d, 10_000L);
		assertEquals(Double.valueOf(61d), gap.valuePct());
		assertEquals(SocResult.Method.VOLTAGE_FALLBACK, gap.method());

		var unavailable = PerCellSocEstimator.update(state, REFERENCE, Double.NaN, 20d,
				3_000L, true, Double.NaN, 10_000L);
		assertNull(unavailable.valuePct());
		assertEquals(SocResult.Quality.UNAVAILABLE, unavailable.quality());
	}

	@Test
	public void referenceRolloverBootstrapsAndStableFloatReanchors() {
		var old = new PerCellEstimatorState(50d, 1_000L, "old");
		var rollover = PerCellSocEstimator.update(old, REFERENCE, 180d, 0d,
				2_000L, true, 87d, 10_000L);
		assertEquals(Double.valueOf(87d), rollover.valuePct());
		assertEquals(SocResult.Method.BASELINE_VOLTAGE_BOOTSTRAP, rollover.method());

		var reanchored = PerCellSocEstimator.update(rollover.state(), REFERENCE, 180d, 0d,
				3_000L, true, 87d, true, 10_000L);
		assertEquals(Double.valueOf(100d), reanchored.valuePct());
		assertEquals(SocResult.Method.STABLE_FLOAT_REANCHOR, reanchored.method());
	}

	@Test
	public void resumesWithoutVoltageAfterCurrentReturnsFollowingBootstrap() {
		var bootstrap = PerCellSocEstimator.update(null, REFERENCE, 200d, null,
				null, false, 80d, 10_000L);
		var armed = PerCellSocEstimator.update(bootstrap.state(), REFERENCE, 200d, 20d,
				1_000L, true, null, 10_000L);
		assertEquals(Double.valueOf(80d), armed.valuePct());
		assertEquals(SocResult.Method.RETAINED, armed.method());

		var unavailable = PerCellSocEstimator.update(armed.state(), REFERENCE, 200d, null,
				1_000L, false, null, 10_000L);
		assertNull(unavailable.valuePct());

		var integrated = PerCellSocEstimator.update(unavailable.state(), REFERENCE, 200d, 20d,
				2_000L, true, null, 10_000L);
		assertEquals(79.997222d, integrated.valuePct(), 0.000001d);
		assertEquals(SocResult.Method.BASELINE_COULOMB_COUNTING, integrated.method());
	}

	@Test
	public void corruptReferenceDoesNotFallBackAsIfNoReferenceExisted() {
		var corrupt = PerCellSocEstimator.update(null, REFERENCE, Double.NaN, 20d,
				1_000L, true, 80d, 10_000L);
		assertNull(corrupt.valuePct());
		assertEquals(SocResult.Quality.UNAVAILABLE, corrupt.quality());
	}
}
