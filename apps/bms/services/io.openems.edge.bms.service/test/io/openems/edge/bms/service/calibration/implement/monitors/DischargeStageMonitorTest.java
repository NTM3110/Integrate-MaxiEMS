package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.implement.CalibrationDemoTimeScale;
import io.openems.edge.bms.service.calibration.implement.CalibrationTestFixtures;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;
import io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.CapacityResultMethod;
import io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.EndpointType;
import io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.SafetyState;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

public class DischargeStageMonitorTest {

	@Test
	public void demoTimeScaleMakesTenWallClockMinutesIntegrateAsTenDischargeHours() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		CalibrationStageEvaluation<DischargeStageMonitor.Checkpoint> evaluation = null;
		for (int sample = 0; sample <= 60; sample++) {
			long measurementTime = CalibrationTestFixtures.BASE_TIME + sample * 10_000L;
			evaluation = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
					CalibrationTestFixtures.measurement(configuration, sample + 1, measurementTime, 10.0, 25.0,
							2.0, 2.0, 2.0),
					evaluation == null ? null : evaluation.checkpoint(), measurementTime,
					CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND);
		}
		assertEquals(10.0 / 6.0, evaluation.checkpoint().lastDeltaAh(), 0.000001);
		assertEquals(100.0, evaluation.checkpoint().rawUsedAh(), 0.000001);
	}

	@Test
	public void wrongCurrentDirectionAllowsConfiguredMaximumThenBlocksAndCorrectDirectionResetsCounter() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		CalibrationStageEvaluation<DischargeStageMonitor.Checkpoint> evaluation = null;
		for (int sample = 1; sample <= 3; sample++) {
			long measurementTime = CalibrationTestFixtures.BASE_TIME + sample * 1_000L;
			evaluation = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
					CalibrationTestFixtures.measurement(configuration, sample, measurementTime, -10.0, 25.0,
							2.0, 2.0, 2.0),
					evaluation == null ? null : evaluation.checkpoint(), measurementTime,
					CalibrationDemoTimeScale.REAL_TIME, 3);
			assertTrue(evaluation.blockers().isEmpty());
			assertEquals(sample, evaluation.checkpoint().consecutiveWrongCurrentDirectionSamples());
		}

		long fourthTime = CalibrationTestFixtures.BASE_TIME + 4_000L;
		var blocked = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 4, fourthTime, -10.0, 25.0, 2.0, 2.0, 2.0),
				evaluation.checkpoint(), fourthTime, CalibrationDemoTimeScale.REAL_TIME, 3);
		assertTrue(blocked.blockers().contains(CalibrationBlocker.PHASE_CURRENT_INCONSISTENT));
		assertEquals(4, blocked.checkpoint().consecutiveWrongCurrentDirectionSamples());

		long correctTime = CalibrationTestFixtures.BASE_TIME + 5_000L;
		var recovered = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 5, correctTime, 10.0, 25.0, 2.0, 2.0, 2.0),
				blocked.checkpoint(), correctTime, CalibrationDemoTimeScale.REAL_TIME, 3);
		assertTrue(recovered.blockers().isEmpty());
		assertEquals(0, recovered.checkpoint().consecutiveWrongCurrentDirectionSamples());
		assertEquals(0.0, recovered.checkpoint().lastDeltaAh(), 0.000001);
		assertEquals(blocked.checkpoint().rawUsedAh(), recovered.checkpoint().rawUsedAh(), 0.000001);

		long followingTime = CalibrationTestFixtures.BASE_TIME + 6_000L;
		var following = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 6, followingTime, 10.0, 25.0, 2.0, 2.0, 2.0),
				recovered.checkpoint(), followingTime, CalibrationDemoTimeScale.REAL_TIME, 3);
		assertTrue(following.checkpoint().lastDeltaAh() > 0.0);
	}

	@Test
	public void invalidWrongCurrentDirectionMaximumIsRejected() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var measurement = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				-10.0, 25.0, 2.0, 2.0, 2.0);
		assertThrows(IllegalArgumentException.class,
				() -> new DischargeStageMonitor().evaluate(configuration, CalibrationStageType.DISCHARGE, measurement,
						null, CalibrationTestFixtures.BASE_TIME, CalibrationDemoTimeScale.REAL_TIME, 0));
	}

	@Test
	public void integratesOnceAndRejectsDuplicateRegressedStaleAndMissingCells() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		long firstTime = CalibrationTestFixtures.BASE_TIME;
		var first = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 10, firstTime, 10.0, 25.0, 2.0, 2.0, 2.0), null,
				firstTime);
		var second = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 11, firstTime + 10_000, 10.0, 25.0, 2.0, 2.0,
						2.0),
				first.checkpoint(), firstTime + 10_000);
		assertEquals(10.0 / 360.0, second.checkpoint().rawUsedAh(), 0.000001);

		var duplicate = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 11, firstTime + 40_000, 10.0, 25.0, 2.0, 2.0,
						2.0),
				second.checkpoint(), firstTime + 40_000);
		assertTrue(duplicate.blockers().contains(CalibrationBlocker.MEASUREMENT_SEQUENCE_REPEATED));
		assertEquals(second.checkpoint().rawUsedAh(), duplicate.checkpoint().rawUsedAh(), 0.0);

		var regressed = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 9, firstTime + 40_000, 10.0, 25.0, 2.0, 2.0,
						2.0),
				second.checkpoint(), firstTime + 40_000);
		assertTrue(regressed.blockers().contains(CalibrationBlocker.MEASUREMENT_SEQUENCE_REGRESSED));

		var stale = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 12, firstTime, 10.0, 25.0, 2.0, 2.0, 2.0),
				second.checkpoint(), firstTime + 60_001);
		assertTrue(stale.blockers().contains(CalibrationBlocker.SNAPSHOT_STALE));

		var missingCell = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 12, firstTime + 40_000, 10.0, 25.0, 2.0, 2.0),
				second.checkpoint(), firstTime + 40_000);
		assertTrue(missingCell.blockers().contains(CalibrationBlocker.CELL_DATA_INCOMPLETE));
		assertEquals(second.checkpoint().perCellState(), missingCell.checkpoint().perCellState());
	}


	@Test
	public void acceptsNewGenerationWithMonotonicSourceTime() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		var first = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 100, CalibrationTestFixtures.BASE_TIME, 12.0, 25.0,
						2.0, 2.0, 2.0),
				null, CalibrationTestFixtures.BASE_TIME);
		var restarted = CalibrationTestFixtures.measurement(configuration, UUID.randomUUID(), 1,
				CalibrationTestFixtures.BASE_TIME + 10_000, 12.0, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				2.0, 2.0, 2.0);
		var evaluation = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE, restarted,
				first.checkpoint(), CalibrationTestFixtures.BASE_TIME + 10_000);
		assertTrue(evaluation.blockers().isEmpty());
		assertEquals(0.0, evaluation.checkpoint().rawUsedAh(), 0.0);
	}

	@Test
	public void newGenerationResetsMutableAnchorsButPreservesKnownCutoffEndpoints() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		var anchor = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, 10.0, 25.0,
						2.0, 2.0, 2.0),
				null, CalibrationTestFixtures.BASE_TIME);
		var cutoff = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 10_000, 10.0,
						25.0, 1.79, 1.90, 1.90),
				anchor.checkpoint(), CalibrationTestFixtures.BASE_TIME + 10_000);

		var restartedMeasurement = CalibrationTestFixtures.measurement(configuration, UUID.randomUUID(), 1,
				CalibrationTestFixtures.BASE_TIME + 20_000, 10.0, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				2.10, 2.20, 2.30);
		var restarted = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE, restartedMeasurement,
				cutoff.checkpoint(), CalibrationTestFixtures.BASE_TIME + 20_000);

		assertTrue(restarted.blockers().isEmpty());
		assertTrue(Boolean.TRUE.equals(restarted.checkpoint().perCellState().get(1).cutoffReached()));
		assertEquals(1.79, restarted.checkpoint().perCellState().get(1).cutoffVoltageV(), 0.000001);
		assertEquals(2.20, restarted.checkpoint().perCellState().get(2).lastVoltageV(), 0.000001);
		assertEquals(2.30, restarted.checkpoint().perCellState().get(3).lastVoltageV(), 0.000001);
		assertTrue(restarted.checkpoint().newCutoffEndpoints().isEmpty());
	}

	@Test
	public void sameGenerationAfterWorkerDowntimeBecomesAnchorWithoutIntegratingGap() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		var first = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 100, CalibrationTestFixtures.BASE_TIME, 12.0, 25.0,
						2.0, 2.0, 2.0),
				null, CalibrationTestFixtures.BASE_TIME);
		long afterDowntime = CalibrationTestFixtures.BASE_TIME
				+ (configuration.sampleIntervalSeconds() + 30L) * 1_000L;
		var anchored = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 101, afterDowntime, 12.0, 25.0, 2.0, 2.0, 2.0),
				first.checkpoint(), afterDowntime);

		assertTrue(anchored.blockers().isEmpty());
		assertEquals(first.checkpoint().rawUsedAh(), anchored.checkpoint().rawUsedAh(), 0.0);
		assertEquals(0.0, anchored.checkpoint().lastDeltaAh(), 0.0);
	}

	@Test
	public void cutoffUsesFirstMeasuredSnapshotPerCellAndCompletesAfterAllCells() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		var first = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, 10.0, 20.0, 2.0,
						2.0, 2.0),
				null, CalibrationTestFixtures.BASE_TIME);
		var firstCutoff = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 10_000, 10.0,
						30.0, 1.79, 1.90, 1.90),
				first.checkpoint(), CalibrationTestFixtures.BASE_TIME + 10_000);
		assertEquals(Recommendation.CONTINUE, firstCutoff.recommendation());
		assertEquals(1, firstCutoff.checkpoint().newCutoffEndpoints().size());
		var cellOne = firstCutoff.checkpoint().perCellState().get(1);
		assertEquals(1.79, cellOne.cutoffVoltageV(), 0.000001);
		assertEquals(firstCutoff.checkpoint().rawUsedAh(), cellOne.cellUsableCapacityAh(), 0.000001);

		var cutoff = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 3, CalibrationTestFixtures.BASE_TIME + 20_000, 10.0,
						30.0, 1.76, 1.78, 1.77),
				firstCutoff.checkpoint(), CalibrationTestFixtures.BASE_TIME + 20_000);
		assertEquals(Recommendation.READY_TO_COMPLETE, cutoff.recommendation());
		assertEquals(2, cutoff.checkpoint().newCutoffEndpoints().size());
		assertEquals(EndpointType.CUTOFF, cutoff.checkpoint().endpoint().type());
		assertEquals(CapacityResultMethod.FULL_CUTOFF, cutoff.checkpoint().endpoint().resultMethod());
		assertEquals(26.666666, cutoff.checkpoint().endpoint().representativeTemperatureC(), 0.000001);
		assertTrue(cutoff.checkpoint().endpoint().kcFactor() > 0);

		var postEndpoint = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 3, CalibrationTestFixtures.BASE_TIME + 40_000, 100.0,
						25.0, 1.70, 1.70, 1.70),
				cutoff.checkpoint(), CalibrationTestFixtures.BASE_TIME + 40_000);
		assertEquals(cutoff.checkpoint(), postEndpoint.checkpoint());
	}

	@Test
	public void partialDodGetsMilestoneAndNeverClaimsFullCapacitySoh() {
		var configuration = CalibrationTestFixtures.periodic(DischargeCurrentDirection.POSITIVE, 35.0);
		var monitor = new DischargeStageMonitor();
		var first = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, 12_600.0, 25.0,
						2.0, 2.0, 2.0),
				null, CalibrationTestFixtures.BASE_TIME);
		var endpoint = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 10_000,
						12_600.0, 25.0, 2.0, 2.0, 2.0),
				first.checkpoint(), CalibrationTestFixtures.BASE_TIME + 10_000);
		assertEquals(EndpointType.TARGET_DOD, endpoint.checkpoint().endpoint().type());
		assertEquals(CapacityResultMethod.PARTIAL_DOD_COMPARISON,
				endpoint.checkpoint().endpoint().resultMethod());
		assertNull(endpoint.checkpoint().endpoint().capacitySohPercent());
		assertNull(endpoint.checkpoint().endpoint().kcFactor());
		assertTrue(endpoint.milestones().stream().anyMatch(milestone -> milestone.value() == 35.0));
	}

	@Test
	public void outOfRangeKcTemperatureBlocksOfficialCutoffResult() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		var endpoint = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, 10.0, 46.0,
						1.80, 1.79, 1.78),
				null, CalibrationTestFixtures.BASE_TIME);
		assertEquals(Recommendation.BLOCKED, endpoint.recommendation());
		assertTrue(endpoint.blockers().contains(CalibrationBlocker.KC_TEMPERATURE_OUT_OF_RANGE));
		assertNull(endpoint.checkpoint().endpoint().correctedAh());
		assertFalse(endpoint.checkpoint().endpoint().resultMethod() == CapacityResultMethod.PARTIAL_DOD_COMPARISON);
	}

	@Test
	public void safetyFloorLatchesOnceFreezesAhAndAcceptsNearZeroConfirmationMeasurement() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new DischargeStageMonitor();
		var anchor = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, 10.0, 25.0,
						2.0, 2.0, 2.0), null, CalibrationTestFixtures.BASE_TIME);
		var latched = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 10_000,
						10.0, 25.0, configuration.safetyFloorVoltagePerCell() - 0.01, 2.0, 2.0),
				anchor.checkpoint(), CalibrationTestFixtures.BASE_TIME + 10_000);

		assertEquals(SafetyState.LATCHED, latched.checkpoint().safetyState());
		assertEquals(latched.checkpoint().rawUsedAh(), latched.checkpoint().frozenUsedAh(), 0.0);
		assertEquals(1, latched.checkpoint().newSafetyTriggers().size());
		assertTrue(latched.persistenceReasons().contains(CalibrationStageEvaluation.PersistenceReason.SAFETY_FLOOR));
		assertEquals(Recommendation.CONTINUE, latched.recommendation());

		var stopped = monitor.evaluate(configuration, CalibrationStageType.DISCHARGE,
				CalibrationTestFixtures.measurement(configuration, 3, CalibrationTestFixtures.BASE_TIME + 20_000,
						0.0, 25.0, configuration.safetyFloorVoltagePerCell() - 0.02, 2.0, 2.0),
				latched.checkpoint(), CalibrationTestFixtures.BASE_TIME + 20_000);
		assertTrue(stopped.blockers().isEmpty());
		assertEquals(latched.checkpoint().rawUsedAh(), stopped.checkpoint().rawUsedAh(), 0.0);
		assertEquals(latched.checkpoint().frozenUsedAh(), stopped.checkpoint().frozenUsedAh());
		assertTrue(stopped.checkpoint().newSafetyTriggers().isEmpty());
		assertFalse(stopped.persistenceReasons().contains(CalibrationStageEvaluation.PersistenceReason.SAFETY_FLOOR));
	}
}
