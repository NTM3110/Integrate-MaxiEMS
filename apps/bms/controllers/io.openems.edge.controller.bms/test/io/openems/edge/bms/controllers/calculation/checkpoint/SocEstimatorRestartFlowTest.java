package io.openems.edge.bms.controllers.calculation.checkpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.contracts.SocEstimatorCheckpointStore;
import io.openems.edge.bms.service.calculation.model.EstimatorMode;
import io.openems.edge.bms.controllers.calculation.estimator.PerCellSocEstimator;
import io.openems.edge.bms.controllers.calculation.estimator.SocResult;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

/** Mock integration coverage for calculator mode, checkpoint and restart flow. */
public class SocEstimatorRestartFlowTest {

	private static final String STRING_KEY = "str1";
	private static final String REFERENCE_IDENTITY = "42:2:60:1:hash";
	private static final EstimatorReferenceIdentity REFERENCE = new EstimatorReferenceIdentity(
			42L, 2L, 60L, 1, "hash");
	private static final double MEASURED_CAPACITY_AH = 180d;
	private static final double DISCHARGE_CURRENT_A = 18d;
	private static final long STARTED_AT = 1_000L;
	private static final long AFTER_ONE_HOUR = STARTED_AT + 3_600_000L;
	private static final long AFTER_RESTART_AND_HALF_HOUR = AFTER_ONE_HOUR + 1_800_000L;
	private static final long MAXIMUM_GAP_MILLIS = 7_200_000L;

	@Test
	public void restoresSavedSocAndContinuesCoulombCountingAfterRestart() {
		assertTrue(EstimatorMode.AUTO.baselineAware());
		assertTrue(EstimatorMode.BASELINE_V1.baselineAware());
		assertFalse(EstimatorMode.LEGACY.baselineAware());

		var bootstrap = PerCellSocEstimator.update(null, REFERENCE_IDENTITY, MEASURED_CAPACITY_AH,
				DISCHARGE_CURRENT_A, STARTED_AT, true, 80d, MAXIMUM_GAP_MILLIS);
		assertEquals(SocResult.Method.BASELINE_VOLTAGE_BOOTSTRAP, bootstrap.method());
		assertEquals(80d, bootstrap.valuePct(), 0d);

		var beforeRestart = PerCellSocEstimator.update(bootstrap.state(), REFERENCE_IDENTITY,
				MEASURED_CAPACITY_AH, DISCHARGE_CURRENT_A, AFTER_ONE_HOUR, true, 75d,
				MAXIMUM_GAP_MILLIS);
		assertEquals(SocResult.Method.BASELINE_COULOMB_COUNTING, beforeRestart.method());
		assertEquals(70d, beforeRestart.valuePct(), 0.000001d);

		var store = new FakeCheckpointStore();
		store.publish(checkpoint(beforeRestart));

		var restored = SocEstimatorCheckpointValidator.restore(store.latest(STRING_KEY).orElseThrow(), STRING_KEY,
				1, REFERENCE_IDENTITY, AFTER_RESTART_AND_HALF_HOUR, MAXIMUM_GAP_MILLIS).orElseThrow();
		assertEquals(70d, restored.get(1).socPct(), 0.000001d);

		var afterRestart = PerCellSocEstimator.update(restored.get(1), REFERENCE_IDENTITY,
				MEASURED_CAPACITY_AH, DISCHARGE_CURRENT_A, AFTER_RESTART_AND_HALF_HOUR, true, 73d,
				MAXIMUM_GAP_MILLIS);
		assertEquals(SocResult.Method.BASELINE_COULOMB_COUNTING, afterRestart.method());
		assertEquals(65d, afterRestart.valuePct(), 0.000001d);
	}

	@Test
	public void rejectsCheckpointFromAnotherBaselineAndBootstrapsNewReference() {
		var previous = PerCellSocEstimator.update(null, REFERENCE_IDENTITY, MEASURED_CAPACITY_AH,
				DISCHARGE_CURRENT_A, STARTED_AT, true, 70d, MAXIMUM_GAP_MILLIS);
		var store = new FakeCheckpointStore();
		store.publish(checkpoint(previous));

		var newReferenceIdentity = "43:3:70:1:new-hash";
		assertTrue(SocEstimatorCheckpointValidator.restore(store.latest(STRING_KEY).orElseThrow(), STRING_KEY,
				1, newReferenceIdentity, AFTER_ONE_HOUR, MAXIMUM_GAP_MILLIS).isEmpty());

		var newReferenceResult = PerCellSocEstimator.update(null, newReferenceIdentity, 175d,
				DISCHARGE_CURRENT_A, AFTER_ONE_HOUR, true, 76d, MAXIMUM_GAP_MILLIS);
		assertEquals(SocResult.Method.BASELINE_VOLTAGE_BOOTSTRAP, newReferenceResult.method());
		assertEquals(76d, newReferenceResult.valuePct(), 0d);
	}

	private static SocEstimatorCheckpoint checkpoint(SocResult result) {
		var state = result.state();
		return new SocEstimatorCheckpoint(SocEstimatorCheckpoint.CURRENT_SCHEMA_VERSION, STRING_KEY, 1,
				UUID.randomUUID(), 7, state.currentMeasuredAt(), REFERENCE, Map.of(1,
						new SocEstimatorCheckpoint.CellState(state.socPct(), state.currentMeasuredAt(),
								state.referenceIdentity())));
	}

	private static final class FakeCheckpointStore implements SocEstimatorCheckpointStore {
		private final Map<String, SocEstimatorCheckpoint> checkpoints = new LinkedHashMap<>();

		@Override
		public Optional<SocEstimatorCheckpoint> latest(String stringKey) {
			return Optional.ofNullable(this.checkpoints.get(stringKey));
		}

		@Override
		public void publish(SocEstimatorCheckpoint checkpoint) {
			this.checkpoints.put(checkpoint.stringKey(), checkpoint);
		}

		@Override
		public void clear(String stringKey) {
			this.checkpoints.remove(stringKey);
		}
	}
}
