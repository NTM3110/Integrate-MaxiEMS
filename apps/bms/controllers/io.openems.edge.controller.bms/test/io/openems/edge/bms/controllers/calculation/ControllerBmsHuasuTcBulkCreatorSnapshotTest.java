package io.openems.edge.bms.controllers.calculation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.common.channel.value.Value;

public class ControllerBmsHuasuTcBulkCreatorSnapshotTest {

	@Test
	public void scheduledCalculationUsesConfiguredTwoSecondSnapshotInterval() {
		assertTrue(ControllerBmsHuasuTcBulkCreatorImpl.shouldRunScheduledCalculation(0, 1_000, 2));
		assertFalse(ControllerBmsHuasuTcBulkCreatorImpl.shouldRunScheduledCalculation(1_000, 2_999, 2));
		assertTrue(ControllerBmsHuasuTcBulkCreatorImpl.shouldRunScheduledCalculation(1_000, 3_000, 2));
	}

	@Test
	public void resetStartsNewGenerationAndSequence() throws Exception {
		var controller = new ControllerBmsHuasuTcBulkCreatorImpl();
		controller.resetCalculationState();
		var firstGeneration = field(controller, "snapshotGeneration", UUID.class);
		setField(controller, "snapshotSequence", 9L);
		putMapField(controller, "calculatedSocValues", 1, 72d);
		putMapField(controller, "calculatedSohValues", 1, 90d);
		setField(controller, "calculatedStringSoc", 72d);
		setField(controller, "calculatedStringSoh", 90d);
		setField(controller, "lastCalculationError", "stale failure");

		controller.resetCalculationState();

		assertNotEquals(firstGeneration, field(controller, "snapshotGeneration", UUID.class));
		assertEquals(0L, field(controller, "snapshotSequence", Long.class).longValue());
		assertNull(controller.calculatedSoc(1));
		assertNull(controller.calculatedSoh(1));
		assertNull(controller.calculatedStringSoc());
		assertNull(controller.calculatedStringSoh());
		assertNull(controller.lastCalculationError());
	}

	private static void putMapField(Object target, String name, Object key, Object value) throws Exception {
		var declared = target.getClass().getDeclaredField(name);
		declared.setAccessible(true);
		var map = declared.get(target);
		map.getClass().getMethod("put", Object.class, Object.class).invoke(map, key, value);
	}

	@Test
	public void currentProvenanceDistinguishesFreshRetainedMissingAndFailed() {
		var freshValue = new Value<Integer>(null, 1234);
		var measuredAt = measuredAt(freshValue);
		var fresh = ControllerBmsHuasuTcBulkCreatorImpl.adaptCurrent(freshValue, false, null, measuredAt - 1);

		assertEquals(Double.valueOf(1.234), fresh.current());
		assertEquals(Long.valueOf(measuredAt), fresh.measuredAt());
		assertEquals(BmsCalculationSnapshot.CurrentQuality.FRESH, fresh.quality());
		assertNull(fresh.warning());

		var retained = ControllerBmsHuasuTcBulkCreatorImpl.adaptCurrent(freshValue, false,
				snapshot(1.234, measuredAt), measuredAt - 1);
		assertEquals(BmsCalculationSnapshot.CurrentQuality.RETAINED, retained.quality());
		assertEquals("CURRENT_VALUE_RETAINED", retained.warning());

		var missing = ControllerBmsHuasuTcBulkCreatorImpl.adaptCurrent(new Value<Integer>(null, null), false, null,
				measuredAt - 1);
		assertNull(missing.current());
		assertNull(missing.measuredAt());
		assertEquals(BmsCalculationSnapshot.CurrentQuality.MISSING, missing.quality());
		assertEquals("CURRENT_DATA_UNAVAILABLE", missing.warning());
		var missingWithRetainedValue = ControllerBmsHuasuTcBulkCreatorImpl.adaptCurrent(
				new Value<Integer>(null, null), false, snapshot(1.234, measuredAt), measuredAt - 1);
		assertEquals(Double.valueOf(1.234), missingWithRetainedValue.current());
		assertEquals(Long.valueOf(measuredAt), missingWithRetainedValue.measuredAt());
		assertEquals(BmsCalculationSnapshot.CurrentQuality.MISSING, missingWithRetainedValue.quality());

		var failed = ControllerBmsHuasuTcBulkCreatorImpl.adaptCurrent(new Value<Integer>(null, null), true,
				snapshot(1.234, measuredAt), measuredAt - 1);
		assertEquals(Double.valueOf(1.234), failed.current());
		assertEquals(Long.valueOf(measuredAt), failed.measuredAt());
		assertEquals(BmsCalculationSnapshot.CurrentQuality.COMMUNICATION_FAILED, failed.quality());
		assertEquals("MODBUS_COMMUNICATION_FAILED", failed.warning());
	}

	@Test
	public void cellProvenanceRetainsOriginalTimeAndRecoversOnTheNextCompleteRead() {
		long measuredAt = 1_000L;
		var previousCell = new BmsCalculationSnapshot.Cell(1, 2.2, 25.0, 1_800, 70.0, 90.0, null, null,
				null, null, measuredAt, BmsCalculationSnapshot.CellQuality.FRESH);
		var previous = new BmsCalculationSnapshot(UUID.randomUUID(), 1, 2_000L, 1, 25.0, 0.0, 2_000L,
				BmsCalculationSnapshot.CurrentQuality.FRESH, null, 70.0, 90.0, 2.2, 1.1, 2.2, 25.0,
				1_800.0, 2.2, 1, 2.2, 1, 25.0, 1, 25.0, 1, 1_800, 1, 1_800, 1,
				Map.of(1, previousCell), null);

		var firstFailure = ControllerBmsHuasuTcBulkCreatorImpl.cellProvenance(previous, 1, 3_000L, false);
		assertEquals(Long.valueOf(measuredAt), firstFailure.measuredAt());
		assertEquals(BmsCalculationSnapshot.CellQuality.RETAINED, firstFailure.quality());

		var repeatedFailure = ControllerBmsHuasuTcBulkCreatorImpl.cellProvenance(previous, 1, 8_000L, false);
		assertEquals(Long.valueOf(measuredAt), repeatedFailure.measuredAt());
		assertEquals(BmsCalculationSnapshot.CellQuality.RETAINED, repeatedFailure.quality());

		var recovery = ControllerBmsHuasuTcBulkCreatorImpl.cellProvenance(previous, 1, 9_000L, true);
		assertEquals(Long.valueOf(9_000L), recovery.measuredAt());
		assertEquals(BmsCalculationSnapshot.CellQuality.FRESH, recovery.quality());

		var noHistory = ControllerBmsHuasuTcBulkCreatorImpl.cellProvenance(null, 1, 9_000L, false);
		assertNull(noHistory.measuredAt());
		assertEquals(BmsCalculationSnapshot.CellQuality.UNAVAILABLE, noHistory.quality());

		var incompleteCell = new BmsCalculationSnapshot.Cell(1, 2.2, null, 1_800, null, null, null, null,
				null, null, measuredAt, BmsCalculationSnapshot.CellQuality.RETAINED);
		var incompleteHistory = new BmsCalculationSnapshot(UUID.randomUUID(), 2, 2_000L, 1, 25.0, 0.0,
				2_000L, BmsCalculationSnapshot.CurrentQuality.FRESH, null, null, null, 2.2, 1.1, 2.2,
				25.0, 1_800.0, 2.2, 1, 2.2, 1, 25.0, 1, 25.0, 1, 1_800, 1, 1_800, 1,
				Map.of(1, incompleteCell), null);
		var incomplete = ControllerBmsHuasuTcBulkCreatorImpl.cellProvenance(incompleteHistory, 1, 10_000L, false);
		assertNull(incomplete.measuredAt());
		assertEquals(BmsCalculationSnapshot.CellQuality.UNAVAILABLE, incomplete.quality());
	}

	@Test
	public void preGenerationCurrentIsRetainedEvenWithoutPreviousSnapshot() {
		var value = new Value<Integer>(null, -500);
		var measuredAt = measuredAt(value);
		var provenance = ControllerBmsHuasuTcBulkCreatorImpl.adaptCurrent(value, false, null, measuredAt);

		assertEquals(Double.valueOf(-0.5), provenance.current());
		assertEquals(Long.valueOf(measuredAt), provenance.measuredAt());
		assertEquals(BmsCalculationSnapshot.CurrentQuality.RETAINED, provenance.quality());
	}

	@Test
	public void midpointUsesFloorHalfAndRejectsIncompleteOrNonFiniteCells() {
		assertEquals(Double.valueOf(4.5), ControllerBmsHuasuTcBulkCreatorImpl.midpointVoltage(5,
				new Double[] { 2.2, 2.3, 2.4, 2.5, 2.6 }));
		assertNull(ControllerBmsHuasuTcBulkCreatorImpl.midpointVoltage(5, new Double[] { 2.2 }));
		assertNull(ControllerBmsHuasuTcBulkCreatorImpl.midpointVoltage(4,
				new Double[] { 2.2, Double.NaN, 2.4, 2.5 }));
		assertNull(ControllerBmsHuasuTcBulkCreatorImpl.midpointVoltage(1, new Double[] { 2.2 }));
	}

	@Test
	public void missingAmbientDuringStartupRemainsUnavailableWithoutUnboxingNull() {
		assertNull(ControllerBmsHuasuTcBulkCreatorImpl.ambientTemperature(null, null));

		var previous = new BmsCalculationSnapshot(UUID.randomUUID(), 1, 1_000, 0, 24.5, null, null,
				BmsCalculationSnapshot.CurrentQuality.MISSING, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, Map.of(), null);
		assertEquals(Double.valueOf(24.5),
				ControllerBmsHuasuTcBulkCreatorImpl.ambientTemperature(null, previous));
		assertEquals(Double.valueOf(25.3),
				ControllerBmsHuasuTcBulkCreatorImpl.ambientTemperature(253, previous));
	}

	@Test
	public void baselineAwareStartupWithNoReadyCellsKeepsStringEstimatesUnavailable() {
		var unavailable = new Double[] { null, null, null };
		assertNull(ControllerBmsHuasuTcBulkCreatorImpl.aggregateStringEstimate(
				unavailable, 3, true, null, true));
		assertNull(ControllerBmsHuasuTcBulkCreatorImpl.aggregateStringEstimate(
				unavailable, 3, true, null, false));

		var partial = new Double[] { null, 72d, 81d };
		assertEquals(Double.valueOf(72d), ControllerBmsHuasuTcBulkCreatorImpl.aggregateStringEstimate(
				partial, 3, true, null, true));
		assertEquals(Double.valueOf(72d), ControllerBmsHuasuTcBulkCreatorImpl.aggregateStringEstimate(
				partial, 3, true, null, false));
	}

	@Test
	public void legacyStartupRetainsPreviousStringEstimateUntilCellsAreReady() {
		var unavailable = new Double[] { null, null };
		assertEquals(Double.valueOf(64d), ControllerBmsHuasuTcBulkCreatorImpl.aggregateStringEstimate(
				unavailable, 2, false, 64d, true));
		assertEquals(Double.valueOf(90d), ControllerBmsHuasuTcBulkCreatorImpl.aggregateStringEstimate(
				unavailable, 2, false, 90d, false));
	}

	@Test
	public void estimatorReferenceIdentityAndFreshnessAreStrictAndDeterministic() {
		var now = Instant.parse("2026-09-10T00:00:00Z");
		var curve = new InitialBaselineReferenceDataset.CurveVersion(2, 60, 3, 1, 200, 180, 190,
				1, 1, "content", now);
		var dataset = new InitialBaselineReferenceDataset(42, UUID.randomUUID(), "str1", 1, 200, now, now,
				UUID.randomUUID(), curve, null, null, Map.of(), List.of(), List.of());

		assertEquals("42:2:60:3:content", ControllerBmsHuasuTcBulkCreatorImpl.referenceIdentity(dataset));
		assertTrue(ControllerBmsHuasuTcBulkCreatorImpl.fresh(now, now.plusSeconds(120).toEpochMilli(), 120));
		assertFalse(ControllerBmsHuasuTcBulkCreatorImpl.fresh(now, now.plusSeconds(121).toEpochMilli(), 120));
		assertFalse(ControllerBmsHuasuTcBulkCreatorImpl.fresh(now.plusSeconds(1), now.toEpochMilli(), 120));
		assertEquals(20d, BmsCurrentDirectionMapping.NEGATIVE.toDomainCurrent(-20d), 0d);
		assertEquals(20d, BmsCurrentDirectionMapping.POSITIVE.toDomainCurrent(20d), 0d);
	}

	@Test
	public void aggregateKeepsProvenanceFromTheExactPublishedCellSet() {
		var retained = new EstimatorProvenance("BASELINE_COULOMB_COUNTING", "PUBLISHED_BASELINE",
				EstimatorQuality.RETAINED, "retained cell", null);
		var fresh = new EstimatorProvenance("BASELINE_COULOMB_COUNTING", "PUBLISHED_BASELINE",
				EstimatorQuality.VALID, "fresh cell", null);
		var cells = Map.of(
				1, new BmsCalculationSnapshot.Cell(1, 2.1, 25d, 1_800, 70d, 80d, null,
						"MODBUS_COMMUNICATION_FAILED", retained, retained),
				2, new BmsCalculationSnapshot.Cell(2, 2.2, 25d, 1_800, 75d, 85d, null, null, fresh, fresh));

		assertSame(retained, ControllerBmsHuasuTcBulkCreatorImpl.aggregateProvenance(70d, cells, true));
		assertSame(retained, ControllerBmsHuasuTcBulkCreatorImpl.aggregateProvenance(80d, cells, false));
	}

	private static long measuredAt(Value<?> value) {
		return value.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static BmsCalculationSnapshot snapshot(Double current, long currentMeasuredAt) {
		return new BmsCalculationSnapshot(UUID.randomUUID(), 1, currentMeasuredAt, 0, null, current, currentMeasuredAt,
				BmsCalculationSnapshot.CurrentQuality.FRESH, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, Map.of(), null);
	}

	private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static <T> T field(Object target, String name, Class<T> type) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return type.cast(field.get(target));
	}
}
