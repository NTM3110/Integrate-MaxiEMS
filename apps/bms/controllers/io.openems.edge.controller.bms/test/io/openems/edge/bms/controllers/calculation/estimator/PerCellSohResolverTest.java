package io.openems.edge.bms.controllers.calculation.estimator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

public class PerCellSohResolverTest {

	@Test
	public void resolvesResistanceThenPerCellProvisionalThenAuthoritativeCapacity() {
		var resistance = PerCellSohResolver.resolve(1, 200d, 1_800, 1_800d, null, null);
		assertEquals(Double.valueOf(100d), resistance.valuePct());
		assertEquals(SohResult.Method.RESISTANCE_ESTIMATE, resistance.method());

		var beforeCutoff = PerCellSohResolver.resolve(1, 200d, 1_800, 2_000d, null,
				active(false, null));
		assertEquals(SohResult.Method.IN_PROGRESS, beforeCutoff.method());
		assertEquals(SohResult.Quality.PROVISIONAL, beforeCutoff.quality());

		var afterCutoff = PerCellSohResolver.resolve(1, 200d, 1_800, 2_000d, null,
				active(true, 180d));
		assertEquals(Double.valueOf(90d), afterCutoff.valuePct());
		assertEquals(SohResult.Method.IN_PROGRESS_CAPACITY, afterCutoff.method());

		var authoritative = PerCellSohResolver.resolve(1, 200d, 1_800, 9_000d,
				baseline(181.123456d), active(true, 120d));
		assertEquals(90.561728d, authoritative.valuePct(), 0.0000001d);
		assertEquals(SohResult.Method.AUTHORITATIVE_BASELINE, authoritative.method());
		assertEquals(SohResult.Quality.AUTHORITATIVE, authoritative.quality());
	}

	@Test
	public void invalidEvidenceIsUnavailableInsteadOfZero() {
		var missingResistance = PerCellSohResolver.resolve(1, 200d, 1_800, null, null, null);
		assertNull(missingResistance.valuePct());
		assertEquals(SohResult.Method.UNAVAILABLE, missingResistance.method());

		var missingCapacity = PerCellSohResolver.resolve(2, 200d, 1_800, 1_800d,
				baseline(180d), null);
		assertNull(missingCapacity.valuePct());
		assertEquals(SohResult.Quality.UNAVAILABLE, missingCapacity.quality());
	}

	@Test
	public void clampsCapacityBasedSohAndRejectsUnpublishedDataset() {
		var authoritative = PerCellSohResolver.resolve(1, 200d, 1_800, 1_800d,
				baseline(220d), null);
		assertEquals(Double.valueOf(100d), authoritative.valuePct());

		var now = Instant.parse("2026-09-10T00:00:00Z");
		var cutoff = new InitialBaselineReferenceDataset.CellCutoff(1, 70, 1.8, now, 180d);
		var unpublished = new InitialBaselineReferenceDataset(42, UUID.randomUUID(), "str1", 1, 200,
				now, now, UUID.randomUUID(), null, null, null, Map.of(), List.of(cutoff), List.of());
		var unavailable = PerCellSohResolver.resolve(1, 200d, 1_800, 1_800d, unpublished, null);
		assertNull(unavailable.valuePct());
		assertEquals(SohResult.Quality.UNAVAILABLE, unavailable.quality());
	}

	private static ActiveCalibrationEstimatorContext active(boolean reached, Double capacity) {
		return new ActiveCalibrationEstimatorContext("str1", 42, 50, 60, 1, "INITIAL_BASELINE", "DISCHARGE",
				"CONTROLLED_DISCHARGE", UUID.randomUUID(), 10, 1_000, 1_100,
				Map.of(1, new ActiveCalibrationEstimatorContext.CellProgress(1, reached, capacity)));
	}

	private static InitialBaselineReferenceDataset baseline(double capacity) {
		var now = Instant.parse("2026-09-10T00:00:00Z");
		var curve = new InitialBaselineReferenceDataset.CurveVersion(2, 60, 1, 1, 200, capacity, capacity,
				1, 1, "hash", now);
		var cutoff = new InitialBaselineReferenceDataset.CellCutoff(1, 70, 1.8, now, capacity);
		return new InitialBaselineReferenceDataset(42, UUID.randomUUID(), "str1", 1, 200, now, now,
				UUID.randomUUID(), curve, null, null, Map.of(), List.of(cutoff), List.of());
	}
}
