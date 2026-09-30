package io.openems.edge.bms.service.calculation.implement.checkpoint;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

public class AsyncSocEstimatorCheckpointStoreTest {

	@Test
	public void persistenceBoundaryRejectsMalformedStateWithoutRepair() {
		var valid = checkpoint(50);
		assertTrue(AsyncSocEstimatorCheckpointStore.persistable(valid));
		assertFalse(AsyncSocEstimatorCheckpointStore.persistable(checkpoint(Double.NaN)));
		assertFalse(AsyncSocEstimatorCheckpointStore.persistable(checkpoint(-1)));
		assertFalse(AsyncSocEstimatorCheckpointStore.persistable(checkpoint(101)));
		var incomplete = new SocEstimatorCheckpoint(1, "str1", 2, UUID.randomUUID(), 1, 10_000,
				valid.reference(), Map.of(1, valid.cells().get(1)));
		assertFalse(AsyncSocEstimatorCheckpointStore.persistable(incomplete));
	}

	@Test
	public void pendingPublicationCoalescesToLatestPerString() throws Exception {
		var store = new AsyncSocEstimatorCheckpointStore();
		var first = checkpoint(50);
		var second = checkpoint(55);

		store.publish(first);
		store.publish(second);

		var field = AsyncSocEstimatorCheckpointStore.class.getDeclaredField("pending");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		var pending = (Map<String, SocEstimatorCheckpoint>) field.get(store);
		assertTrue(pending.size() == 1);
		assertTrue(pending.get("str1") == second);
	}

	@Test
	public void delayedOlderPublicationCannotReplaceNewestPendingState() throws Exception {
		var store = new AsyncSocEstimatorCheckpointStore();
		var newest = checkpoint(55, 11_000, 2);
		var delayedOlder = checkpoint(50, 10_000, 1);

		store.publish(newest);
		store.publish(delayedOlder);

		var field = AsyncSocEstimatorCheckpointStore.class.getDeclaredField("pending");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		var pending = (Map<String, SocEstimatorCheckpoint>) field.get(store);
		assertTrue(pending.get("str1") == newest);
	}

	private static SocEstimatorCheckpoint checkpoint(double soc) {
		return checkpoint(soc, 10_000, 1);
	}

	private static SocEstimatorCheckpoint checkpoint(double soc, long measuredAt, long sourceSequence) {
		var identity = "42:2:60:1:hash";
		return new SocEstimatorCheckpoint(1, "str1", 2, UUID.randomUUID(), sourceSequence, measuredAt,
				new EstimatorReferenceIdentity(42L, 2L, 60L, 1, "hash"), Map.of(
						1, new SocEstimatorCheckpoint.CellState(soc, measuredAt, identity),
						2, new SocEstimatorCheckpoint.CellState(60, measuredAt, identity)));
	}
}
