package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.Test;

import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy.Source;

public class CalculateDailyProductionEnergyTest {

	private static final Duration MAXIMUM_GAP = Duration.ofMinutes(5);

	@Test
	public void testDeviceTodayHasPriorityAndMissingInputIsNotRepeated() {
		var sut = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP);
		var first = sut.update(instant("2026-07-17T10:00:00Z"), 500L, 10_000L, 1_000);
		assertEquals(Long.valueOf(500), first.energyWh());
		assertEquals(Source.DEVICE_TODAY, first.source());
		assertFalse(first.invalidData());

		var second = sut.update(instant("2026-07-17T10:01:00Z"), 520L, 10_020L, 1_000);
		assertEquals(Long.valueOf(520), second.energyWh());

		var missing = sut.update(instant("2026-07-17T10:02:00Z"), null, null, null);
		assertNull(missing.energyWh());
		assertEquals(Source.UNAVAILABLE, missing.source());
	}

	@Test
	public void testMiddayStartDoesNotPublishPartialTotalFallback() {
		var sut = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP);
		assertNull(sut.update(instant("2026-07-17T12:00:00Z"), null, 10_000L, 3_600).energyWh());
		assertNull(sut.update(instant("2026-07-17T12:01:00Z"), null, 10_060L, 3_600).energyWh());

		var restored = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP, sut.snapshot());
		assertNull(restored.update(instant("2026-07-17T12:02:00Z"), null, 10_120L, 3_600).energyWh());
	}

	@Test
	public void testTotalFallbackAcrossMidnightAndRestart() {
		var sut = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP);
		sut.update(instant("2026-07-17T23:59:00Z"), null, 11_000L, 3_600);
		var midnight = sut.update(instant("2026-07-18T00:00:00Z"), null, 11_060L, 3_600);
		assertEquals(Source.TOTAL_COUNTER, midnight.source());
		assertEquals(Long.valueOf(60), midnight.energyWh());

		var restored = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP, sut.snapshot());
		var next = restored.update(instant("2026-07-18T00:01:00Z"), null, 11_120L, 3_600);
		assertEquals(Source.TOTAL_COUNTER, next.source());
		assertEquals(Long.valueOf(120), next.energyWh());
	}

	@Test
	public void testPowerFallbackUsesOnlyPositivePowerAndRejectsLongGap() {
		var sut = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP);
		sut.update(instant("2026-07-17T23:59:00Z"), null, null, 3_600);
		var midnight = sut.update(instant("2026-07-18T00:00:00Z"), null, null, 3_600);
		assertEquals(Long.valueOf(0), midnight.energyWh());
		assertEquals(Source.ACTIVE_POWER, midnight.source());

		var oneMinute = sut.update(instant("2026-07-18T00:01:00Z"), null, null, -500);
		assertEquals(Long.valueOf(60), oneMinute.energyWh());
		var negativePower = sut.update(instant("2026-07-18T00:02:00Z"), null, null, -500);
		assertEquals(Long.valueOf(60), negativePower.energyWh());

		var afterGap = sut.update(instant("2026-07-18T00:10:00Z"), null, null, 3_600);
		assertNull(afterGap.energyWh());
		assertEquals(Source.UNAVAILABLE, afterGap.source());
	}

	@Test
	public void testInvalidCounterAndTimestamp() {
		var sut = new CalculateDailyProductionEnergy(ZoneId.of("UTC"), MAXIMUM_GAP);
		sut.update(instant("2026-07-17T23:59:00Z"), null, 10_000L, 0);
		sut.update(instant("2026-07-18T00:00:00Z"), null, 10_000L, 0);

		var reset = sut.update(instant("2026-07-18T00:01:00Z"), null, 9_000L, 0);
		assertTrue(reset.invalidData());
		assertEquals(Source.ACTIVE_POWER, reset.source());

		var backwards = sut.update(instant("2026-07-17T23:00:00Z"), null, 9_100L, 0);
		assertTrue(backwards.invalidData());
		assertNull(backwards.energyWh());
	}

	@Test
	public void testTimezoneDefinesDayBoundary() {
		var sut = new CalculateDailyProductionEnergy(ZoneId.of("Asia/Ho_Chi_Minh"), MAXIMUM_GAP);
		sut.update(instant("2026-07-17T16:59:00Z"), null, 20_000L, 0);
		var localMidnight = sut.update(instant("2026-07-17T17:00:00Z"), null, 20_010L, 0);
		assertEquals(Source.TOTAL_COUNTER, localMidnight.source());
		assertEquals(Long.valueOf(10), localMidnight.energyWh());
	}

	private static Instant instant(String value) {
		return Instant.parse(value);
	}
}
