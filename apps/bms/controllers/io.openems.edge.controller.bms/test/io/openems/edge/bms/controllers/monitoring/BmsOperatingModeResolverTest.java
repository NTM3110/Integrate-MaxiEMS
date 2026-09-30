package io.openems.edge.bms.controllers.monitoring;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;

public class BmsOperatingModeResolverTest {

	@Test
	public void restartRequiresStableTelemetryBeforeFloat() {
		var resolver = new BmsOperatingModeResolver();
		var generation = UUID.randomUUID();
		var policy = policy(60);
		var first = snapshot(generation, 1, 1_000_000L, 253.0, -0.1);
		var candidate = resolver.resolve(first, -0.1, ActiveCalibrationContext.none(), 253.0, policy,
				first.capturedAt());
		assertEquals(OperatingMode.UNKNOWN, candidate.mode());
		assertEquals(ModeQuality.CANDIDATE, candidate.quality());

		var stable = snapshot(generation, 2, 1_060_000L, 253.0, -0.1);
		var resolved = resolver.resolve(stable, -0.1, ActiveCalibrationContext.none(), 253.0, policy,
				stable.capturedAt());
		assertEquals(OperatingMode.FLOAT, resolved.mode());
		assertEquals(ModeQuality.VALID, resolved.quality());
	}

	@Test
	public void repeatedPhysicalSnapshotDoesNotAdvanceStableWindow() {
		var resolver = new BmsOperatingModeResolver();
		var snapshot = snapshot(UUID.randomUUID(), 1, 1_000_000L, 253.0, -0.1);
		resolver.resolve(snapshot, -0.1, ActiveCalibrationContext.none(), 253.0, policy(60), 1_000_000L);
		var repeated = resolver.resolve(snapshot, -0.1, ActiveCalibrationContext.none(), 253.0, policy(60),
				1_030_000L);
		assertEquals(OperatingMode.UNKNOWN, repeated.mode());
		assertEquals(ModeQuality.CANDIDATE, repeated.quality());
	}

	@Test
	public void activeDischargeResolvesImmediatelyWhenDirectionMatches() {
		var resolver = new BmsOperatingModeResolver();
		var snapshot = snapshot(UUID.randomUUID(), 1, 1_000_000L, 240.0, 20.0);
		var context = new ActiveCalibrationContext(1L, 2L, 3L, "INITIAL_BASELINE", "DISCHARGE",
				"CONTROLLED_DISCHARGE", null);
		var mode = resolver.resolve(snapshot, 20.0, context, 253.0, policy(60), snapshot.capturedAt());
		assertEquals(OperatingMode.DISCHARGING, mode.mode());
		assertEquals(ModeSource.ACTIVE_CALIBRATION, mode.source());
		assertEquals(ModeQuality.VALID, mode.quality());
	}

	@Test
	public void activeProcessWithContradictingDirectionFailsSafeToUnknown() {
		var resolver = new BmsOperatingModeResolver();
		var snapshot = snapshot(UUID.randomUUID(), 1, 1_000_000L, 240.0, -20.0);
		var context = new ActiveCalibrationContext(1L, 2L, 3L, "INITIAL_BASELINE", "DISCHARGE",
				"CONTROLLED_DISCHARGE", null);
		var mode = resolver.resolve(snapshot, -20.0, context, 253.0, policy(60), snapshot.capturedAt());
		assertEquals(OperatingMode.UNKNOWN, mode.mode());
		assertEquals(ModeQuality.CANDIDATE, mode.quality());
	}

	@Test
	public void activeFloatStillRequiresStableTelemetry() {
		var resolver = new BmsOperatingModeResolver();
		var generation = UUID.randomUUID();
		var context = new ActiveCalibrationContext(1L, 2L, 3L, "INITIAL_BASELINE", "INITIAL_CHARGE",
				"FLOAT_SETTLEMENT_TO_VFLOAT", 40.0);
		var first = snapshot(generation, 1, 1_000_000L, 253.0, -0.1);
		var candidate = resolver.resolve(first, -0.1, context, 253.0, policy(60), first.capturedAt());
		assertEquals(OperatingMode.UNKNOWN, candidate.mode());
		assertEquals(ModeSource.ACTIVE_CALIBRATION, candidate.source());
		var stable = snapshot(generation, 2, 1_060_000L, 253.0, -0.1);
		var resolved = resolver.resolve(stable, -0.1, context, 253.0, policy(60), stable.capturedAt());
		assertEquals(OperatingMode.FLOAT, resolved.mode());
		assertEquals(ModeQuality.VALID, resolved.quality());
	}

	@Test
	public void staleEvidenceForcesUnknownAndResetsState() {
		var resolver = new BmsOperatingModeResolver();
		var snapshot = snapshot(UUID.randomUUID(), 1, 1_000_000L, 240.0, 20.0);
		var mode = resolver.resolve(snapshot, 20.0, ActiveCalibrationContext.none(), 253.0, policy(0),
				1_061_000L);
		assertEquals(OperatingMode.UNKNOWN, mode.mode());
		assertEquals(ModeQuality.STALE, mode.quality());
	}

	private static BmsMonitoringPolicy policy(int stableSeconds) {
		var value = BmsMonitoringPolicy.defaults();
		return new BmsMonitoringPolicy(value.snapshotFreshnessSeconds(), value.cellVoltageDeviationFraction(),
				value.batteryVoltageLowV(), value.batteryVoltageHighV(), value.resistanceBaselineMultiplier(),
				value.cellTemperatureAboveAverageC(), value.cellTemperatureAboveAmbientC(),
				value.floatCurrentBaselineMultiplier(), value.chargeOvercurrentCapacityFraction(), value.lowSocPct(),
				value.highAmbientTemperatureC(), value.midpointImbalanceV(), value.currentDeadbandA(),
				value.floatVoltageToleranceV(), stableSeconds);
	}

	private static BmsCalculationSnapshot snapshot(UUID generation, long sequence, long capturedAt, double voltage,
			double current) {
		var cells = Map.of(1, new BmsCalculationSnapshot.Cell(1, voltage, 25.0, 1_800, 90.0, 95.0, 1_800.0, null));
		return new BmsCalculationSnapshot(generation, sequence, capturedAt, 1, 25.0, current, capturedAt,
				CurrentQuality.FRESH, null, 90.0, 95.0, voltage, voltage / 2.0, voltage, 25.0, 1_800.0,
				voltage, 1, voltage, 1, 25.0, 1, 25.0, 1, 1_800, 1, 1_800, 1, cells, null);
	}
}
