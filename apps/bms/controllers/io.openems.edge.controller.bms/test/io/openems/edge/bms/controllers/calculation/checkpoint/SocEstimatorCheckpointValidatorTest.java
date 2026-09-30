package io.openems.edge.bms.controllers.calculation.checkpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

public class SocEstimatorCheckpointValidatorTest {

	private static final long MEASURED_AT = 10_000;
	private static final String IDENTITY = "42:2:60:1:hash";

	@Test
	public void restoresOnlyCompleteFreshMatchingState() {
		var restored = SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(
				1, cell(70), 2, cell(80))), "str1", 2, IDENTITY, MEASURED_AT + 1_000, 2_000);

		assertTrue(restored.isPresent());
		assertEquals(70d, restored.orElseThrow().get(1).socPct(), 0d);
		assertEquals(80d, restored.orElseThrow().get(2).socPct(), 0d);
	}

	@Test
	public void rejectsIncompleteInvalidIdentityVersionRangeAndGap() {
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(70))),
				"str1", 2, IDENTITY, MEASURED_AT + 1_000, 2_000).isEmpty());
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(70), 2, cell(101))),
				"str1", 2, IDENTITY, MEASURED_AT + 1_000, 2_000).isEmpty());
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(70), 2, cell(80))),
				"str2", 2, IDENTITY, MEASURED_AT + 1_000, 2_000).isEmpty());
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(70), 2, cell(80))),
				"str1", 2, "other", MEASURED_AT + 1_000, 2_000).isEmpty());
		var wrongVersion = new SocEstimatorCheckpoint(2, "str1", 2, UUID.randomUUID(), 4, MEASURED_AT,
				reference(), Map.of(1, cell(70), 2, cell(80)));
		assertTrue(SocEstimatorCheckpointValidator.restore(wrongVersion,
				"str1", 2, IDENTITY, MEASURED_AT + 1_000, 2_000).isEmpty());
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(70), 2, cell(80))),
				"str1", 2, IDENTITY, MEASURED_AT + 2_001, 2_000).isEmpty());
	}

	@Test
	public void doesNotCoerceNanOrMissingToZero() {
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(Double.NaN), 2, cell(80))),
				"str1", 2, IDENTITY, MEASURED_AT + 1_000, 2_000).isEmpty());
		assertTrue(SocEstimatorCheckpointValidator.restore(checkpoint(Map.of(1, cell(0), 2,
				new SocEstimatorCheckpoint.CellState(80, null, IDENTITY))), "str1", 2, IDENTITY,
				MEASURED_AT + 1_000, 2_000).isEmpty());
	}

	private static SocEstimatorCheckpoint checkpoint(Map<Integer, SocEstimatorCheckpoint.CellState> cells) {
		return new SocEstimatorCheckpoint(SocEstimatorCheckpoint.CURRENT_SCHEMA_VERSION, "str1", 2,
				UUID.randomUUID(), 4, MEASURED_AT, reference(), cells);
	}

	private static EstimatorReferenceIdentity reference() {
		return new EstimatorReferenceIdentity(42L, 2L, 60L, 1, "hash");
	}

	private static SocEstimatorCheckpoint.CellState cell(double socPct) {
		return new SocEstimatorCheckpoint.CellState(socPct, MEASURED_AT, IDENTITY);
	}
}
