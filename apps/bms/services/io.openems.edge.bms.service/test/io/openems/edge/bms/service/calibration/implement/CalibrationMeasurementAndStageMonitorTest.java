package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationStoreTestAccess;
import io.openems.edge.bms.service.calibration.implement.monitors.ChargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.CooldownStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.FloatChargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.InspectionStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.VocBaselineMonitor;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations.FloatCurrentResult;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

public class CalibrationMeasurementAndStageMonitorTest {

	@Test
	public void workerUsesAuditedProcessFloatPolicyWithoutChangingEquipmentSnapshot() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var properties = new JsonObject();
		properties.addProperty("floatStableDurationMinutes", 30);
		properties.addProperty("floatVoltageToleranceV", 0.5);
		properties.addProperty("floatCurrentMinA", 0.0);
		properties.addProperty("floatCurrentMaxA", 0.1);
		var event = new CalibrationEventModels.WorkerEvent(1, 2, java.util.UUID.randomUUID(), "str1", configuration,
				60, CalibrationStageType.INITIAL_CHARGE, CalibrationEventModels.StageState.ACTIVE, new JsonObject(), null,
				null, null, 1L, 1, "FLOAT_SETTLEMENT_TO_VFLOAT", CalibrationEventModels.ProcessState.ACTIVE,
				"FINAL_SNAPSHOT", properties, null);
		var effective = CalibrationEventWorker.configurationForEvaluation(event);
		assertEquals(30, effective.floatStableDurationMinutes());
		assertEquals(0.5, effective.floatVoltageToleranceV(), 0.000001);
		assertEquals(0.1, effective.floatCurrentMaxA(), 0.000001);
		assertEquals(configuration.floatVoltagePerCell(), effective.floatVoltagePerCell(), 0.000001);
		var measurement = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				-0.05, 25.0, 2.30, 2.30, 2.30);
		assertTrue(CalibrationEventWorker.evaluate(event, measurement, measurement.capturedAt()).checkpoint()
				instanceof FloatChargeStageMonitor.Checkpoint);
	}

	@Test
	public void cooldownDispatchAndCompletionIgnoreLegacyFloatTargetProperties() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var properties = new JsonObject();
		// A previous worker version may have left these in an active Cool-down row.
		properties.addProperty("floatTargetVoltageV", 253.0);
		properties.addProperty("boostTargetVoltageV", 269.5);
		var event = new CalibrationEventModels.WorkerEvent(1, 2, java.util.UUID.randomUUID(), "str1", configuration,
				1, CalibrationStageType.PRE_DISCHARGE_FLOAT, CalibrationEventModels.StageState.ACTIVE, new JsonObject(), null,
				null, null, 1L, 1, CalibrationEventModels.ProcessState.ACTIVE, "FINAL_SNAPSHOT", properties);
		var measurement = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, -0.070,
				25.0, 2.30, 2.30, 2.30);

		var evaluation = CalibrationEventWorker.evaluate(event, measurement, measurement.capturedAt());
		assertTrue(evaluation.checkpoint() instanceof CooldownStageMonitor.Checkpoint);
		assertEquals(Recommendation.READY_TO_COMPLETE, evaluation.recommendation());
		assertTrue(CalibrationStoreTestAccess.allowsAutomaticFinalSnapshotCompletion(event));
	}

	@Test
	public void automaticPeriodicSamplesUseThirtySecondCadenceButQualityChangesAreImmediate() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var periodic = new CalibrationStageEvaluation<>(null, List.of(), List.of(),
				List.of(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE), Recommendation.CONTINUE);
		var qualityChange = new CalibrationStageEvaluation<>(null,
				List.of(CalibrationBlocker.CURRENT_STALE), List.of(),
				List.of(CalibrationStageEvaluation.PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		long persistedAt = CalibrationTestFixtures.BASE_TIME;

		assertTrue(CalibrationSamplePersistencePolicy.reasonsToPersist(periodic, persistedAt + 29_999,
				persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30).isEmpty());
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE),
				CalibrationSamplePersistencePolicy.reasonsToPersist(periodic, persistedAt + 30_000,
						persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30));
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.QUALITY_CHANGE),
				CalibrationSamplePersistencePolicy.reasonsToPersist(qualityChange, persistedAt + 1,
						persistedAt, true, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30));
		assertTrue(CalibrationSamplePersistencePolicy.reasonsToPersist(qualityChange, persistedAt + 2,
				persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30).isEmpty());
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.QUALITY_CHANGE),
				CalibrationSamplePersistencePolicy.reasonsToPersist(periodic, persistedAt + 1,
						persistedAt, true, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30));
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE,
				CalibrationStageEvaluation.PersistenceReason.QUALITY_CHANGE),
				CalibrationSamplePersistencePolicy.reasonsToPersist(periodic, persistedAt + 30_000,
						persistedAt, true, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30));
		assertTrue(CalibrationSamplePersistencePolicy.reasonsToPersist(periodic, persistedAt + 20_000,
				persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.FINAL_SNAPSHOT, 30).isEmpty());
		var finalSnapshot = new CalibrationStageEvaluation<>(null, List.of(), List.of(),
				List.of(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE,
						CalibrationStageEvaluation.PersistenceReason.FINAL_SNAPSHOT), Recommendation.READY_TO_COMPLETE);
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.FINAL_SNAPSHOT),
				CalibrationSamplePersistencePolicy.reasonsToPersist(finalSnapshot, persistedAt + 1,
						persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.FINAL_SNAPSHOT, 30));
		var milestone = new CalibrationStageEvaluation<>(null, List.of(), List.of(),
				List.of(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE,
						CalibrationStageEvaluation.PersistenceReason.DOD_MILESTONE_5_PERCENT),
				Recommendation.CONTINUE);
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.DOD_MILESTONE_5_PERCENT),
				CalibrationSamplePersistencePolicy.reasonsToPersist(milestone, persistedAt + 5_000,
						persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30));
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.PERIODIC_SAMPLE,
				CalibrationStageEvaluation.PersistenceReason.DOD_MILESTONE_5_PERCENT),
				CalibrationSamplePersistencePolicy.reasonsToPersist(milestone, persistedAt + 30_000,
						persistedAt, false, CalibrationProcessDefinition.SamplingPolicy.PERIODIC, 30));
		assertEquals(30, CalibrationDischargeSampling.wallClockSeconds(CalibrationDemoTimeScale.REAL_TIME));
		assertEquals(2,
				CalibrationDischargeSampling.wallClockSeconds(CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND));
	}

	@Test
	public void retainedCurrentDedupeUsesLatestEvaluationInsteadOfLastPersistedSample() {
		assertEquals(Long.valueOf(20_000),
				CalibrationStoreTestAccess.latestEvaluatedCurrentMeasuredAt("20000", 10_000L));
		assertEquals(Long.valueOf(10_000),
				CalibrationStoreTestAccess.latestEvaluatedCurrentMeasuredAt("legacy-invalid", 10_000L));
	}

	@Test
	public void adaptsBothDirectionsAndPreservesSnapshotProvenanceAndCells() {
		var positive = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var negative = CalibrationTestFixtures.initial(DischargeCurrentDirection.NEGATIVE);
		var positiveMeasurement = CalibrationTestFixtures.measurement(positive, 1, CalibrationTestFixtures.BASE_TIME,
				10.0, 25.0, 2.1, 2.1, 2.1);
		var negativeMeasurement = CalibrationTestFixtures.measurement(negative, 1, CalibrationTestFixtures.BASE_TIME,
				-10.0, 25.0, 2.1, 2.1, 2.1);
		assertEquals(10.0, positiveMeasurement.domainCurrentA(), 0.000001);
		assertEquals(10.0, negativeMeasurement.domainCurrentA(), 0.000001);
		assertEquals(CalibrationTestFixtures.GENERATION, negativeMeasurement.snapshotGeneration());
		assertEquals(CalibrationTestFixtures.BASE_TIME, negativeMeasurement.currentMeasuredAt().longValue());
		assertEquals(DischargeCurrentDirection.NEGATIVE, negativeMeasurement.currentDirection());
		assertEquals(7, negativeMeasurement.mappingVersion());
		assertEquals(3, negativeMeasurement.cells().size());
		assertEquals(Integer.valueOf(1_000), negativeMeasurement.cells().get(1).internalResistanceUohm());
	}

	@Test
	public void floatMonitorUsesConfiguredInclusiveRangeAndInvalidatesBadWindow() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new FloatChargeStageMonitor();
		var valid = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, -0.100,
				25.0, 2.30, 2.30, 2.30);
		var evaluation = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, valid, null,
				CalibrationTestFixtures.BASE_TIME);
		assertEquals(Recommendation.CONTINUE, evaluation.recommendation());
		assertEquals(FloatCurrentResult.PASS, evaluation.checkpoint().result());
		assertTrue(evaluation.blockers().isEmpty());
		var upperBound = CalibrationTestFixtures.measurement(configuration, 2,
				CalibrationTestFixtures.BASE_TIME + configuration.floatStableDurationMinutes() * 60_000L, -0.100,
				25.0, 2.30, 2.30, 2.30);
		var complete = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, upperBound,
				evaluation.checkpoint(), upperBound.capturedAt());
		assertEquals(Recommendation.READY_TO_COMPLETE, complete.recommendation());

		var outOfRange = CalibrationTestFixtures.measurement(configuration, 3,
				upperBound.capturedAt() + 1_000, -0.101, 25.0, 2.30, 2.30, 2.30);
		var reset = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, outOfRange,
				complete.checkpoint(), outOfRange.capturedAt());
		assertEquals(Recommendation.CONTINUE, reset.recommendation());
		assertEquals(null, reset.checkpoint().stableSinceMillis());

		var retained = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 4,
				outOfRange.capturedAt() + 1_000, -0.100, 25.0, BmsCalculationSnapshot.CurrentQuality.RETAINED,
				null, 2.30, 2.30, 2.30);
		var invalid = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, retained,
				reset.checkpoint(), retained.capturedAt());
		assertEquals(Recommendation.BLOCKED, invalid.recommendation());
		assertEquals(FloatCurrentResult.DATA_INVALID, invalid.checkpoint().result());
		assertTrue(invalid.checkpoint().invalidWindow());

		var restarted = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 5,
				retained.capturedAt() + 1_000, -0.100, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH,
				null, 2.30, 2.30, 2.30);
		var restartedEvaluation = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT,
				restarted, invalid.checkpoint(), restarted.capturedAt());
		assertEquals(1, restartedEvaluation.checkpoint().sampleCount());
		assertEquals(0.100, restartedEvaluation.checkpoint().maxFloatCurrentA(), 0.000001);
		assertFalse(restartedEvaluation.checkpoint().invalidWindow());
	}

	@Test
	public void demoTimeScaleMakesOneRealSecondCountAsOneCalibrationMinuteForFloatWindows() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new FloatChargeStageMonitor();
		var first = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, -0.100,
				25.0, 2.30, 2.30, 2.30);
		var started = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, first, null,
				first.capturedAt(), CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND);
		var afterOneRealSecond = CalibrationTestFixtures.measurement(configuration, 2,
				CalibrationTestFixtures.BASE_TIME + 1_000, -0.100, 25.0, 2.30, 2.30, 2.30);
		var afterOneRealSecondEvaluation = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE,
				afterOneRealSecond, started.checkpoint(), afterOneRealSecond.capturedAt(),
				CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND);
		assertEquals(60_000, afterOneRealSecondEvaluation.checkpoint().stableDurationMillis());
		assertEquals(Recommendation.CONTINUE, afterOneRealSecondEvaluation.recommendation());
		var afterOneRealMinute = CalibrationTestFixtures.measurement(configuration, 3,
				CalibrationTestFixtures.BASE_TIME + 60_000, -0.100, 25.0, 2.30, 2.30, 2.30);
		var complete = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE,
				afterOneRealMinute, afterOneRealSecondEvaluation.checkpoint(), afterOneRealMinute.capturedAt(),
				CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND);
		assertEquals(configuration.floatStableDurationMinutes() * 60_000L,
				complete.checkpoint().stableDurationMillis());
		assertEquals(Recommendation.READY_TO_COMPLETE, complete.recommendation());
	}

	@Test
	public void demoTimeScaleDoesNotAccelerateCooldownFloat() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new FloatChargeStageMonitor();
		var first = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, -0.100,
				25.0, 2.30, 2.30, 2.30);
		var started = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, first, null,
				first.capturedAt(), CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND);
		var afterOneRealSecond = CalibrationTestFixtures.measurement(configuration, 2,
				CalibrationTestFixtures.BASE_TIME + 1_000, -0.100, 25.0, 2.30, 2.30, 2.30);
		var stillWaiting = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, afterOneRealSecond,
				started.checkpoint(), afterOneRealSecond.capturedAt(), CalibrationDemoTimeScale.DEMO_MINUTE_PER_SECOND);
		assertEquals(1_000, stillWaiting.checkpoint().stableDurationMillis());
		assertEquals(Recommendation.CONTINUE, stillWaiting.recommendation());
	}

	@Test
	public void cooldownCompletesImmediatelyWhenFreshMaxCellTemperatureIsWithinAmbientAllowance() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var source = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME, -0.070,
				25.0, 2.30, 2.30, 2.30);
		var cells = Map.of(
				1, new CalibrationMeasurement.Cell(1, 2.30, 25.0, 1_000, 90.0, 95.0, 1_000.0, null),
				2, new CalibrationMeasurement.Cell(2, 2.30, 25.2, 1_001, 89.0, 94.0, 1_001.0, null),
				3, new CalibrationMeasurement.Cell(3, 2.30, 25.1, 1_002, 88.0, 93.0, 1_002.0, null));
		// Cool-down is decided by the fresh thermal snapshot. A retained/unavailable
		// charge-current reading must not block the thermal completion.
		var withinRange = new CalibrationMeasurement(source.snapshotGeneration(), source.snapshotSequence(),
				source.capturedAt(), source.configuredCellCount(), 25.0, null, null,
				null, BmsCalculationSnapshot.CurrentQuality.RETAINED, "not used by cool-down", source.currentDirection(),
				source.mappingVersion(), source.stringSocPct(), source.stringSohPct(), source.stringVoltageV(),
				source.midpointVoltageV(), source.averageVoltageV(), source.averageTemperatureC(),
				source.averageResistanceUohm(), cells, source.stringWarning());

		var passing = new CooldownStageMonitor().evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT,
				withinRange, null, withinRange.capturedAt());
		assertTrue(passing.blockers().isEmpty());
		assertEquals(25.2, passing.checkpoint().maxCellTemperatureC(), 0.000001);
		assertEquals(0.2, passing.checkpoint().temperatureDeltaC(), 0.000001);
		assertEquals(Recommendation.READY_TO_COMPLETE, passing.recommendation());
		Map<Integer, CalibrationMeasurement.Cell> boundaryCells = Map.of(
				1, cells.get(1), 2, cells.get(2), 3,
				new CalibrationMeasurement.Cell(3, 2.30, 25.30, 1_002, 88.0, 93.0, 1_002.0, null));
		var atBoundary = new CalibrationMeasurement(withinRange.snapshotGeneration(), 2, withinRange.capturedAt() + 1_000,
				withinRange.configuredCellCount(), withinRange.ambientTemperatureC(), withinRange.rawCurrentA(),
				withinRange.domainCurrentA(), null, withinRange.currentQuality(),
				withinRange.currentWarning(), withinRange.currentDirection(), withinRange.mappingVersion(),
				withinRange.stringSocPct(), withinRange.stringSohPct(), withinRange.stringVoltageV(),
				withinRange.midpointVoltageV(), withinRange.averageVoltageV(), withinRange.averageTemperatureC(),
				withinRange.averageResistanceUohm(), boundaryCells, withinRange.stringWarning());
		assertEquals(Recommendation.READY_TO_COMPLETE, new CooldownStageMonitor().evaluate(configuration,
				CalibrationStageType.PRE_DISCHARGE_FLOAT, atBoundary, passing.checkpoint(), atBoundary.capturedAt())
				.recommendation());

		Map<Integer, CalibrationMeasurement.Cell> aboveRangeCells = Map.of(
				1, cells.get(1), 2, cells.get(2), 3,
				new CalibrationMeasurement.Cell(3, 2.30, 25.31, 1_002, 88.0, 93.0, 1_002.0, null));
		var aboveRange = new CalibrationMeasurement(withinRange.snapshotGeneration(), 3, withinRange.capturedAt() + 2_000,
				withinRange.configuredCellCount(), withinRange.ambientTemperatureC(), withinRange.rawCurrentA(),
				withinRange.domainCurrentA(), null, withinRange.currentQuality(),
				withinRange.currentWarning(), withinRange.currentDirection(), withinRange.mappingVersion(),
				withinRange.stringSocPct(), withinRange.stringSohPct(), withinRange.stringVoltageV(),
				withinRange.midpointVoltageV(), withinRange.averageVoltageV(), withinRange.averageTemperatureC(),
				withinRange.averageResistanceUohm(), aboveRangeCells, withinRange.stringWarning());
		var waiting = new CooldownStageMonitor().evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT,
				aboveRange, passing.checkpoint(), aboveRange.capturedAt());
		assertTrue(waiting.blockers().isEmpty());
		assertEquals(Recommendation.CONTINUE, waiting.recommendation());
	}

	@Test
	public void retainedCurrentIsSkippedUntilStaleAndFreshCurrentResumesTheWindow() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new FloatChargeStageMonitor();
		long measuredAt = CalibrationTestFixtures.BASE_TIME;
		var fresh = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 1,
				measuredAt, measuredAt, -0.100, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				2.30, 2.30, 2.30);
		var accepted = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, fresh, null,
				measuredAt);
		assertEquals(1, accepted.checkpoint().sampleCount());

		var retained = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 2,
				measuredAt + 1_000, measuredAt, -0.100, 25.0, BmsCalculationSnapshot.CurrentQuality.RETAINED, null,
				2.30, 2.30, 2.30);
		assertTrue(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, retained, measuredAt + 1_000,
				measuredAt, false));
		// The worker/store drops this evaluation, so its accepted checkpoint remains exact.
		assertEquals(1, accepted.checkpoint().sampleCount());

		long staleAt = measuredAt + configuration.snapshotFreshnessSeconds() * 1_000L;
		var staleRetained = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 3,
				staleAt, measuredAt, -0.100, 25.0, BmsCalculationSnapshot.CurrentQuality.RETAINED, null,
				2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, staleRetained, staleAt,
				measuredAt, false));
		var stale = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, staleRetained,
				accepted.checkpoint(), staleAt);
		assertEquals(List.of(CalibrationBlocker.CURRENT_STALE), stale.blockers());
		assertEquals(1, stale.checkpoint().sampleCount());
		assertTrue(stale.checkpoint().invalidWindow());
		assertTrue(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, staleRetained, staleAt + 1,
				measuredAt, true));

		var resumed = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 4,
				staleAt + 1_000, staleAt + 1_000, -0.100, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, resumed, staleAt + 1_000,
				measuredAt, true));
		var recovered = monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, resumed,
				stale.checkpoint(), staleAt + 1_000);
		assertTrue(recovered.blockers().isEmpty());
		assertEquals(1, recovered.checkpoint().sampleCount());
		assertFalse(recovered.checkpoint().invalidWindow());

		var communicationFailed = CalibrationTestFixtures.measurement(configuration,
				CalibrationTestFixtures.GENERATION, 5, staleAt + 2_000, staleAt + 1_000, -0.100, 25.0,
				BmsCalculationSnapshot.CurrentQuality.COMMUNICATION_FAILED, null, 2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, communicationFailed,
				staleAt + 2_000, staleAt + 1_000, false));
		assertTrue(monitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, communicationFailed,
				recovered.checkpoint(), staleAt + 2_000).blockers()
				.contains(CalibrationBlocker.CURRENT_QUALITY_INVALID));
	}

	@Test
	public void retainedDedupeRejectsNonmatchingAndInvalidCurrentProvenance() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		long measuredAt = CalibrationTestFixtures.BASE_TIME;
		long evaluatedAt = measuredAt + 1_000;
		var fresh = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 1,
				evaluatedAt, measuredAt, -0.050, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, fresh, evaluatedAt,
				measuredAt, false));

		var nonmatching = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 2,
				evaluatedAt, measuredAt + 1, -0.050, 25.0, BmsCalculationSnapshot.CurrentQuality.RETAINED, null,
				2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, nonmatching, evaluatedAt,
				measuredAt, false));

		for (var quality : List.of(BmsCalculationSnapshot.CurrentQuality.MISSING,
				BmsCalculationSnapshot.CurrentQuality.COMMUNICATION_FAILED)) {
			var invalid = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 3,
					evaluatedAt, measuredAt, -0.050, 25.0, quality, null, 2.30, 2.30, 2.30);
			assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, invalid, evaluatedAt,
					measuredAt, false));
			assertTrue(CalibrationMeasurementValidator.validate(configuration, invalid, evaluatedAt,
					CalibrationMeasurementValidator.Requirements.charge())
					.contains(CalibrationBlocker.CURRENT_QUALITY_INVALID));
		}

		var timestampMissing = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 4,
				evaluatedAt, null, -0.050, 25.0, BmsCalculationSnapshot.CurrentQuality.TIMESTAMP_MISSING, null,
				2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, timestampMissing, evaluatedAt,
				null, false));
		var timestampBlockers = CalibrationMeasurementValidator.validate(configuration, timestampMissing, evaluatedAt,
				CalibrationMeasurementValidator.Requirements.charge());
		assertTrue(timestampBlockers.contains(CalibrationBlocker.CURRENT_STALE));
		assertTrue(timestampBlockers.contains(CalibrationBlocker.CURRENT_QUALITY_INVALID));

		var retainedWithoutTimestamp = CalibrationTestFixtures.measurement(configuration,
				CalibrationTestFixtures.GENERATION, 5, evaluatedAt, null, -0.050, 25.0,
				BmsCalculationSnapshot.CurrentQuality.RETAINED, null, 2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, retainedWithoutTimestamp, evaluatedAt,
				null, true));

		var futureRetained = CalibrationTestFixtures.measurement(configuration, CalibrationTestFixtures.GENERATION, 6,
				evaluatedAt, evaluatedAt + 1, -0.050, 25.0, BmsCalculationSnapshot.CurrentQuality.RETAINED, null,
				2.30, 2.30, 2.30);
		assertFalse(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, futureRetained, evaluatedAt,
				evaluatedAt + 1, false));
		assertTrue(CalibrationRetainedCurrentPolicy.shouldSkip(configuration, futureRetained, evaluatedAt,
				evaluatedAt + 1, true));
	}

	@Test
	public void inspectionRequiresCompleteConfiguredCellShape() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var missingCell = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				-1.0, 25.0, 2.30, 2.30);
		var evaluation = new VocBaselineMonitor().evaluate(configuration, CalibrationStageType.BASELINE_CAPTURE,
				missingCell, null, CalibrationTestFixtures.BASE_TIME, 0.1);
		assertTrue(evaluation.blockers().contains(CalibrationBlocker.CELL_DATA_INCOMPLETE));
		assertFalse(evaluation.blockers().contains(CalibrationBlocker.CONFIGURATION_MISMATCH));
	}

	@Test
	public void baselineCaptureEmitsOnlyFinalSnapshotPersistence() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var measurement = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				-0.050, 25.0, 2.30, 2.30, 2.30);
		var evaluation = new VocBaselineMonitor().evaluate(configuration, CalibrationStageType.BASELINE_CAPTURE,
				measurement, null, CalibrationTestFixtures.BASE_TIME, 0.1);
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.FINAL_SNAPSHOT),
				evaluation.persistenceReasons());
		assertEquals(CalibrationStageEvaluation.Recommendation.READY_TO_COMPLETE, evaluation.recommendation());
	}

	@Test
	public void vocCaptureWaitsForCurrentInsideAppliedDeadband() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var currentStillFlowing = CalibrationTestFixtures.measurement(configuration, 1,
				CalibrationTestFixtures.BASE_TIME, -0.200, 25.0, 2.30, 2.30, 2.30);
		var waiting = new VocBaselineMonitor().evaluate(configuration, CalibrationStageType.BASELINE_CAPTURE,
				currentStillFlowing, null, CalibrationTestFixtures.BASE_TIME, 0.05);
		assertEquals(Recommendation.CONTINUE, waiting.recommendation());
		assertFalse(waiting.persistenceReasons().contains(CalibrationStageEvaluation.PersistenceReason.FINAL_SNAPSHOT));

		var openCircuit = CalibrationTestFixtures.measurement(configuration, 2,
				CalibrationTestFixtures.BASE_TIME + 1_000, -0.040, 25.0, 2.30, 2.30, 2.30);
		var captured = new VocBaselineMonitor().evaluate(configuration, CalibrationStageType.BASELINE_CAPTURE,
				openCircuit, waiting.checkpoint(), CalibrationTestFixtures.BASE_TIME + 1_000, 0.05);
		assertEquals(Recommendation.READY_TO_COMPLETE, captured.recommendation());
		assertEquals(List.of(CalibrationStageEvaluation.PersistenceReason.FINAL_SNAPSHOT),
				captured.persistenceReasons());
	}

	@Test
	public void monthlyInspectionUsesPeriodicStrictFloatLimit() {
		var configuration = CalibrationTestFixtures.periodic(DischargeCurrentDirection.POSITIVE, 50.0);
		var measurement = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				-0.200, 25.0, 2.30, 2.30, 2.30);
		var evaluation = new InspectionStageMonitor().evaluate(configuration,
				CalibrationStageType.MONTHLY_INSPECTION, measurement, null, CalibrationTestFixtures.BASE_TIME);
		assertEquals(FloatCurrentResult.FAIL, evaluation.checkpoint().floatCurrentResult());
		assertFalse(evaluation.checkpoint().floatCurrentPass());
		assertTrue(evaluation.blockers().contains(CalibrationBlocker.FLOAT_CURRENT_CHECK_FAILED));

		var lower = CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 1_000,
				-0.100, 25.0, 2.30, 2.30, 2.30);
		var second = new InspectionStageMonitor().evaluate(configuration,
				CalibrationStageType.MONTHLY_INSPECTION, lower, evaluation.checkpoint(),
				CalibrationTestFixtures.BASE_TIME + 1_000);
		assertEquals(0.100, second.checkpoint().maxFloatCurrentA(), 0.000001);
		assertEquals(FloatCurrentResult.PASS, second.checkpoint().floatCurrentResult());
		assertTrue(second.checkpoint().floatCurrentPass());
		assertFalse(second.blockers().contains(CalibrationBlocker.FLOAT_CURRENT_CHECK_FAILED));
		assertEquals(InspectionStageMonitor.ComparisonResult.UNAVAILABLE,
				second.checkpoint().comparisonResult());
		assertFalse(second.blockers().contains(CalibrationBlocker.MONTHLY_DEVIATION_EXCEEDED));
	}

	@Test
	public void monthlyInspectionCalculatesAuthoritativeLiveMetricsAndPreviousDeviation() {
		var configuration = CalibrationTestFixtures.periodic(DischargeCurrentDirection.POSITIVE, 50.0);
		var monitor = new InspectionStageMonitor();
		var measurement = CalibrationTestFixtures.measurement(configuration, 1,
				CalibrationTestFixtures.BASE_TIME, -0.100, 25.0, 2.30, 2.31, 2.32);
		var passing = monitor.evaluate(configuration, CalibrationStageType.MONTHLY_INSPECTION, measurement, null,
				CalibrationTestFixtures.BASE_TIME, 77L, 0.080, CalibrationTestFixtures.BASE_TIME - 1_000);
		assertEquals(2.30, passing.checkpoint().midpointVoltageV(), 0.000001);
		assertEquals(6.93, passing.checkpoint().stringVoltageV(), 0.000001);
		assertEquals(0.100, passing.checkpoint().floatCurrentA(), 0.000001);
		assertTrue(passing.checkpoint().floatCurrentPass());
		assertEquals(77L, passing.checkpoint().previousMonthlyEventId().longValue());
		assertEquals(25.0, passing.checkpoint().relativeDeviationPercent(), 0.000001);
		assertEquals(InspectionStageMonitor.ComparisonResult.PASS, passing.checkpoint().comparisonResult());
		assertTrue(passing.checkpoint().ambientTemperaturePass());
		assertTrue(passing.blockers().isEmpty());

		var hot = CalibrationTestFixtures.withAmbient(measurement, 34.0);
		var failing = monitor.evaluate(configuration, CalibrationStageType.MONTHLY_INSPECTION, hot, null,
				CalibrationTestFixtures.BASE_TIME, 76L, 0.050, CalibrationTestFixtures.BASE_TIME - 2_000);
		assertEquals(100.0, failing.checkpoint().relativeDeviationPercent(), 0.000001);
		assertEquals(InspectionStageMonitor.ComparisonResult.FAIL, failing.checkpoint().comparisonResult());
		assertFalse(failing.checkpoint().ambientTemperaturePass());
		assertTrue(failing.blockers().contains(CalibrationBlocker.MONTHLY_DEVIATION_EXCEEDED));
		assertTrue(failing.blockers().contains(CalibrationBlocker.AMBIENT_TEMPERATURE_HIGH));

		var negativePrior = monitor.evaluate(configuration, CalibrationStageType.MONTHLY_INSPECTION, measurement, null,
				CalibrationTestFixtures.BASE_TIME, 75L, -0.080, CalibrationTestFixtures.BASE_TIME - 3_000);
		assertEquals(25.0, negativePrior.checkpoint().relativeDeviationPercent(), 0.000001);
		assertEquals(InspectionStageMonitor.ComparisonResult.PASS,
				negativePrior.checkpoint().comparisonResult());

		var zeroPrior = monitor.evaluate(configuration, CalibrationStageType.MONTHLY_INSPECTION, measurement, null,
				CalibrationTestFixtures.BASE_TIME, 74L, 0.0, CalibrationTestFixtures.BASE_TIME - 4_000);
		assertEquals(InspectionStageMonitor.ComparisonResult.NOT_COMPARABLE_ZERO,
				zeroPrior.checkpoint().comparisonResult());
		assertFalse(zeroPrior.blockers().contains(CalibrationBlocker.MONTHLY_DEVIATION_EXCEEDED));
	}

	@Test
	public void monthlyInspectionRejectsMissingOrNonFiniteDerivedScalarInputs() {
		var configuration = CalibrationTestFixtures.periodic(DischargeCurrentDirection.POSITIVE, 50.0);
		var measurement = CalibrationTestFixtures.measurement(configuration, 1,
				CalibrationTestFixtures.BASE_TIME, -0.100, 25.0, 2.30, 2.31, 2.32);
		var missingTotal = CalibrationTestFixtures.withStringVoltage(measurement, null);
		var missing = new InspectionStageMonitor().evaluate(configuration,
				CalibrationStageType.MONTHLY_INSPECTION, missingTotal, null, CalibrationTestFixtures.BASE_TIME);
		assertTrue(missing.blockers().contains(CalibrationBlocker.CELL_DATA_INCOMPLETE));
		assertEquals(Recommendation.BLOCKED, missing.recommendation());

		var infiniteTotal = CalibrationTestFixtures.withStringVoltage(measurement, Double.POSITIVE_INFINITY);
		var nonFinite = new InspectionStageMonitor().evaluate(configuration,
				CalibrationStageType.MONTHLY_INSPECTION, infiniteTotal, null, CalibrationTestFixtures.BASE_TIME);
		assertTrue(nonFinite.blockers().contains(CalibrationBlocker.CELL_DATA_INCOMPLETE));
	}

	@Test
	public void chargeReachesVboostWithoutLegacyRechargeDurationWindow() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var monitor = new ChargeStageMonitor();
		var first = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				-10.0, 25.0, 2.30, 2.30, 2.30);
		var initial = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, first, null,
				CalibrationTestFixtures.BASE_TIME);
		assertEquals(Recommendation.CONTINUE, initial.recommendation());

		var second = CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 1_000,
				-10.0, 25.0, 2.45, 2.45, 2.45);
		var boost = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, second, initial.checkpoint(),
				CalibrationTestFixtures.BASE_TIME + 1_000);
		assertEquals(1_000, boost.checkpoint().durationMillis());
		assertEquals(Recommendation.READY_TO_COMPLETE, boost.recommendation());

		var recovery = monitor.evaluate(configuration, CalibrationStageType.RECHARGE, second,
				initial.checkpoint(), second.capturedAt());
		assertEquals(Recommendation.READY_TO_COMPLETE, recovery.recommendation());
	}

	@Test
	public void chargeAcceptsOnlyTheCellChannelQuantizationAllowanceAtVboost() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.NEGATIVE);
		var monitor = new ChargeStageMonitor();
		var first = CalibrationTestFixtures.measurement(configuration, 1, CalibrationTestFixtures.BASE_TIME,
				10.0, 33.0, 2.30, 2.30, 2.30);
		var initial = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, first, null,
				first.capturedAt());
		assertEquals(Recommendation.CONTINUE, initial.recommendation());

		double target = CalibrationCalculations.temperatureTargets(configuration, 33.0).boostStringVoltageV();
		var quantizedEndpoint = CalibrationTestFixtures.withStringVoltage(
				CalibrationTestFixtures.measurement(configuration, 2, CalibrationTestFixtures.BASE_TIME + 1_000,
						10.0, 33.0, 2.41, 2.41, 2.41),
				target - configuration.cellCount() * 0.0005);
		var complete = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, quantizedEndpoint,
				initial.checkpoint(), quantizedEndpoint.capturedAt());
		assertTrue(complete.blockers().isEmpty());
		assertEquals(Recommendation.READY_TO_COMPLETE, complete.recommendation());

		var taperedBeforeTransition = CalibrationTestFixtures.withStringVoltage(
				CalibrationTestFixtures.measurement(configuration, 3, CalibrationTestFixtures.BASE_TIME + 2_000,
						0.07, 33.0, 2.41, 2.41, 2.41),
				target);
		var phaseBlocked = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE,
				taperedBeforeTransition, complete.checkpoint(), taperedBeforeTransition.capturedAt());
		assertTrue(phaseBlocked.blockers().contains(CalibrationBlocker.PHASE_CURRENT_INCONSISTENT));

		var belowAllowance = CalibrationTestFixtures.withStringVoltage(
				CalibrationTestFixtures.measurement(configuration, 4, CalibrationTestFixtures.BASE_TIME + 3_000,
						10.0, 33.0, 2.40, 2.40, 2.40),
				target - configuration.cellCount() * 0.0005 - 0.000001);
		var continuing = monitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, belowAllowance,
				complete.checkpoint(), belowAllowance.capturedAt());
		assertTrue(continuing.blockers().isEmpty());
		assertEquals(Recommendation.CONTINUE, continuing.recommendation());
	}

	@Test
	public void nonDischargeMonitorsRejectRepeatedAndRegressedSequences() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var chargeMeasurement = CalibrationTestFixtures.measurement(configuration, 5, CalibrationTestFixtures.BASE_TIME,
				-1.0, 25.0, 2.30, 2.30, 2.30);
		var chargeMonitor = new ChargeStageMonitor();
		var charge = chargeMonitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, chargeMeasurement, null,
				CalibrationTestFixtures.BASE_TIME);
		assertTrue(chargeMonitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, chargeMeasurement,
				charge.checkpoint(), CalibrationTestFixtures.BASE_TIME).blockers()
				.contains(CalibrationBlocker.MEASUREMENT_SEQUENCE_REPEATED));

		var floatMonitor = new FloatChargeStageMonitor();
		var floating = floatMonitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT,
				chargeMeasurement, null, CalibrationTestFixtures.BASE_TIME);
		var regressed = CalibrationTestFixtures.measurement(configuration, 4, CalibrationTestFixtures.BASE_TIME + 1,
				-0.05, 25.0, 2.30, 2.30, 2.30);
		assertTrue(floatMonitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, regressed,
				floating.checkpoint(), regressed.capturedAt()).blockers()
				.contains(CalibrationBlocker.MEASUREMENT_SEQUENCE_REGRESSED));

		var inspectionMonitor = new InspectionStageMonitor();
		var inspection = inspectionMonitor.evaluate(configuration, CalibrationStageType.PREREQUISITES,
				chargeMeasurement, null, CalibrationTestFixtures.BASE_TIME);
		assertTrue(inspectionMonitor.evaluate(configuration, CalibrationStageType.PREREQUISITES, chargeMeasurement,
				inspection.checkpoint(), CalibrationTestFixtures.BASE_TIME).blockers()
				.contains(CalibrationBlocker.MEASUREMENT_SEQUENCE_REPEATED));
	}

	@Test
	public void nonDischargeMonitorsRejectTimeRegressionWithoutReplacingAcceptedCheckpoint() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var accepted = CalibrationTestFixtures.measurement(configuration, 5, CalibrationTestFixtures.BASE_TIME + 1_000,
				-1.0, 25.0, 2.30, 2.30, 2.30);
		var timeRegressed = CalibrationTestFixtures.measurement(configuration, java.util.UUID.randomUUID(), 1,
				CalibrationTestFixtures.BASE_TIME, -1.0, 25.0, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				2.30, 2.30, 2.30);

		var chargeMonitor = new ChargeStageMonitor();
		var charge = chargeMonitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, accepted, null,
				accepted.capturedAt());
		var rejectedCharge = chargeMonitor.evaluate(configuration, CalibrationStageType.INITIAL_CHARGE, timeRegressed,
				charge.checkpoint(), accepted.capturedAt());
		assertTrue(rejectedCharge.blockers().contains(CalibrationBlocker.MEASUREMENT_TIME_REGRESSED));
		assertSame(charge.checkpoint(), rejectedCharge.checkpoint());

		var floatMonitor = new FloatChargeStageMonitor();
		var floating = floatMonitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, accepted, null,
				accepted.capturedAt());
		var rejectedFloat = floatMonitor.evaluate(configuration, CalibrationStageType.PRE_DISCHARGE_FLOAT, timeRegressed,
				floating.checkpoint(), accepted.capturedAt());
		assertTrue(rejectedFloat.blockers().contains(CalibrationBlocker.MEASUREMENT_TIME_REGRESSED));
		assertSame(floating.checkpoint(), rejectedFloat.checkpoint());

		var inspectionMonitor = new InspectionStageMonitor();
		var inspection = inspectionMonitor.evaluate(configuration, CalibrationStageType.PREREQUISITES, accepted, null,
				accepted.capturedAt());
		var rejectedInspection = inspectionMonitor.evaluate(configuration, CalibrationStageType.PREREQUISITES,
				timeRegressed, inspection.checkpoint(), accepted.capturedAt());
		assertTrue(rejectedInspection.blockers().contains(CalibrationBlocker.MEASUREMENT_TIME_REGRESSED));
		assertSame(inspection.checkpoint(), rejectedInspection.checkpoint());
	}
}
