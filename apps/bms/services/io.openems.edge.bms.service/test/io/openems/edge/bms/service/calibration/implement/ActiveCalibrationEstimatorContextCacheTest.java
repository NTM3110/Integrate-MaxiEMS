package io.openems.edge.bms.service.calibration.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;

public class ActiveCalibrationEstimatorContextCacheTest {

	@Test
	public void publishesImmutableContextAndClearsEndedEvent() {
		var cache = new ActiveCalibrationEstimatorContextCache();
		var context = context(60, 1, 10, 1_000);
		cache.publish(Map.of("str1", context));

		assertEquals(context, cache.activeByStringKey("str1").orElseThrow());
		assertThrows(UnsupportedOperationException.class,
				() -> context.cells().put(2, new ActiveCalibrationEstimatorContext.CellProgress(2, false, null)));

		cache.publish(Map.of());
		assertFalse(cache.activeByStringKey("str1").isPresent());
	}

	@Test
	public void rejectsReplayButAcceptsNewAttemptAndSourceSequence() {
		var cache = new ActiveCalibrationEstimatorContextCache();
		var newest = context(60, 2, 20, 2_000);
		cache.publish(Map.of("str1", newest));
		cache.publish(Map.of("str1", context(60, 2, 19, 3_000)));
		assertEquals(20, cache.activeByStringKey("str1").orElseThrow().sourceSequence());

		cache.publish(Map.of("str1", context(60, 3, 1, 4_000)));
		assertEquals(3, cache.activeByStringKey("str1").orElseThrow().processAttemptNumber());
		assertTrue(cache.activeByStringKey("str1").orElseThrow().initialBaselineDischarge());
	}

	private static ActiveCalibrationEstimatorContext context(long processId, int attempt, long sequence,
			long publishedAt) {
		return new ActiveCalibrationEstimatorContext("str1", 42, 50, processId, attempt, "INITIAL_BASELINE",
				"DISCHARGE", "CONTROLLED_DISCHARGE", UUID.fromString("00000000-0000-0000-0000-000000000001"),
				sequence, publishedAt - 10, publishedAt,
				Map.of(1, new ActiveCalibrationEstimatorContext.CellProgress(1, false, null)));
	}
}
