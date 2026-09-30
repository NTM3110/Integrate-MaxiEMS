package io.openems.edge.bms.service.monitoring.model;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmEvaluation;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;

public class BmsMonitoringModbusMapTest {

	@Test
	public void shouldExposeIdentitySignedMeasurementsAndStableAlarmBits() {
		var generation = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000");
		var frame = frame(generation);
		var registers = BmsMonitoringModbusMap.registers(frame);

		assertEquals(1, registers.get(6001).intValue());
		assertEquals(1, registers.get(6002).intValue());
		assertEquals(0x1020, registers.get(6011).intValue());
		assertEquals(0x3040, registers.get(6012).intValue());
		assertEquals(0, registers.get(6019).intValue());
		assertEquals(77, registers.get(6022).intValue());
		assertEquals(Alarm.LOW_SOC.mask() | Alarm.SENSOR_MODULE_FAILURE.mask(),
				u32(registers, 6007));
		assertEquals(1, registers.get(6061 + Alarm.LOW_SOC.bit()).intValue());
		assertEquals(1, registers.get(6061 + Alarm.SENSOR_MODULE_FAILURE.bit()).intValue());
		assertEquals(0, registers.get(6061 + Alarm.HIGH_CELL_VOLTAGE.bit()).intValue());
		var expectedMeasurements = new int[] { 25_325, 12_660, -1_575, -3_989, 15, 2_291, 2_309,
				2_302, 1_901, 1_800, 251, 260, 254, 9_995, 1_025, 8_756, 8_001 };
		for (var index = 0; index < expectedMeasurements.length; index++) {
			assertEquals("measurement " + index, expectedMeasurements[index], s32(registers, 6101 + index * 2));
		}
		for (var alarm : Alarm.values()) {
			assertEquals((frame.alarmMask() & alarm.mask()) == 0 ? 0 : 1,
					registers.get(6061 + alarm.bit()).intValue());
		}
	}

	@Test
	public void shouldUseExplicitSentinelWithoutTurningUnavailableIntoZero() {
		var missing = BmsMonitoringModbusMap.registers(null);
		assertEquals(1, missing.get(6001).intValue());
		assertEquals(0, missing.get(6002).intValue());
		assertEquals(0xffff, missing.get(6011).intValue());
		assertEquals(BmsMonitoringModbusMap.UNAVAILABLE_S32, s32(missing, 6101));
		assertEquals(0xffff, missing.get(6061).intValue());

		var frame = frame(UUID.randomUUID());
		var values = frame.string();
		var partial = new BmsMonitoringSnapshot.StringMeasurements(values.batteryVoltageV(), null,
				values.batteryCurrentA(), values.batteryPowerW(), values.floatCurrentA(), values.minCellVoltageV(),
				values.maxCellVoltageV(), values.averageCellVoltageV(), values.maxCellResistanceUohm(),
				values.averageCellResistanceUohm(), values.ambientTemperatureC(), values.maxCellTemperatureC(),
				values.averageCellTemperatureC(), values.batterySocPct(), null, values.batterySohPct(),
				values.minimumCellSohPct());
		var updated = new BmsMonitoringSnapshot(frame.stringKey(), frame.snapshotGeneration(), frame.snapshotSequence(),
				frame.capturedAt(), frame.currentMeasuredAt(), frame.currentQuality(), frame.stale(), frame.reference(),
				partial, frame.cells(), frame.thresholds(), frame.activeCalibration(), frame.operatingMode(),
				frame.currentDirectionMapping(), frame.activeAlarms(), frame.alarmMask(), frame.alarmDetails(),
				frame.qualityWarning());
		var registers = BmsMonitoringModbusMap.registers(updated);
		assertEquals(BmsMonitoringModbusMap.UNAVAILABLE_S32, s32(registers, 6103));
		assertEquals(BmsMonitoringModbusMap.UNAVAILABLE_S32, s32(registers, 6129));
	}

	@Test
	public void shouldExposeStaleAndRetainedQualityWithoutChangingAlarmBits() {
		var base = frame(UUID.randomUUID());
		var stale = new BmsMonitoringSnapshot(base.stringKey(), base.snapshotGeneration(), base.snapshotSequence(),
				base.capturedAt(), base.currentMeasuredAt(), CurrentQuality.RETAINED, true, base.reference(),
				base.string(), base.cells(), base.thresholds(), base.activeCalibration(),
				new ModeEvidence(OperatingMode.UNKNOWN, ModeSource.NONE, base.capturedAt(), base.snapshotGeneration(),
						base.snapshotSequence(), ModeQuality.STALE, "stale"), base.currentDirectionMapping(),
				base.activeAlarms(), base.alarmMask(), base.alarmDetails(), "stale");
		var registers = BmsMonitoringModbusMap.registers(stale);
		assertEquals(1, registers.get(6003).intValue());
		assertEquals(1, registers.get(6004).intValue());
		assertEquals(2, registers.get(6006).intValue());
		assertEquals(stale.alarmMask(), u32(registers, 6007));
	}

	@Test
	public void unavailableReferenceAndStaleConditionsNeverLookCleared() {
		var base = frame(UUID.randomUUID());
		var noReference = qualityFrame(base, false, Map.of(
				Alarm.CELL_RESISTANCE, AlarmEvaluation.UNAVAILABLE,
				Alarm.FLOAT_CHARGE_OVERCURRENT, AlarmEvaluation.UNAVAILABLE));
		var registers = BmsMonitoringModbusMap.registers(noReference);
		assertEquals(1, registers.get(6002).intValue()); // Live measurements remain available.
		assertEquals(25_325, s32(registers, 6101));
		assertEquals(0xffff, registers.get(6061 + Alarm.CELL_RESISTANCE.bit()).intValue());
		assertEquals(0xffff, registers.get(6061 + Alarm.FLOAT_CHARGE_OVERCURRENT.bit()).intValue());
		assertEquals(0, registers.get(6061 + Alarm.BATTERY_VOLTAGE_HIGH.bit()).intValue());
		var stale = qualityFrame(base, true, Map.of(Alarm.LOW_SOC, AlarmEvaluation.UNAVAILABLE));
		registers = BmsMonitoringModbusMap.registers(stale);
		assertEquals(1, registers.get(6003).intValue());
		assertEquals(0xffff, registers.get(6061 + Alarm.LOW_SOC.bit()).intValue());
		assertEquals(1, registers.get(6061 + Alarm.SENSOR_MODULE_FAILURE.bit()).intValue());
		assertEquals(base.alarmMask(), u32(registers, 6007)); // Mask is not repurposed as availability bits.
	}

	@Test
	public void estimatorProvenanceIsAdditiveAndDoesNotChangeTheRegisterMap() {
		var base = frame(UUID.randomUUID());
		var provenance = new EstimatorProvenance("AUTHORITATIVE_BASELINE", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", null);
		var enriched = new BmsMonitoringSnapshot(base.stringKey(), base.snapshotGeneration(), base.snapshotSequence(),
				base.capturedAt(), base.currentMeasuredAt(), base.currentQuality(), base.stale(), base.reference(),
				base.string(), base.cells(), base.thresholds(), base.activeCalibration(), base.operatingMode(),
				base.currentDirectionMapping(), base.activeAlarms(), base.alarmMask(), base.alarmDetails(),
				base.qualityWarning(), base.calculationSnapshot(), base.publicationGeneration(), base.publicationRevision(),
				base.alarmEvaluations(), provenance, provenance);
		assertEquals(BmsMonitoringModbusMap.registers(base), BmsMonitoringModbusMap.registers(enriched));
	}

	private static BmsMonitoringSnapshot qualityFrame(BmsMonitoringSnapshot base, boolean stale,
			Map<Alarm, AlarmEvaluation> evaluations) {
		return new BmsMonitoringSnapshot(base.stringKey(), base.snapshotGeneration(), base.snapshotSequence(),
				base.capturedAt(), base.currentMeasuredAt(), base.currentQuality(), stale, null, base.string(),
				base.cells(), base.thresholds(), base.activeCalibration(), base.operatingMode(),
				base.currentDirectionMapping(), base.activeAlarms(), base.alarmMask(), base.alarmDetails(),
				stale ? "stale" : "reference unavailable", base.calculationSnapshot(), base.publicationGeneration(),
				base.publicationRevision() + 1, evaluations);
	}

	private static BmsMonitoringSnapshot frame(UUID generation) {
		var at = Instant.ofEpochMilli(1_789_000_000_123L);
		var reference = new BmsMonitoringSnapshot.ReferenceIdentity(42, 2, 100, 1, 200, 201, "curve-hash");
		var values = new BmsMonitoringSnapshot.StringMeasurements(253.25, 126.60, -15.75, -3989.0, 0.15,
				2.291, 2.309, 2.302, 1_901, 1_800.4, 25.1, 26.0, 25.4, 99.95, 10.25, 87.56, 80.01);
		var mode = new ModeEvidence(OperatingMode.FLOAT, ModeSource.TELEMETRY_INFERENCE, at, generation, 77,
				ModeQuality.VALID, "Stable float");
		var active = Set.of(Alarm.LOW_SOC, Alarm.SENSOR_MODULE_FAILURE);
		return new BmsMonitoringSnapshot("str1", generation, 77, at, at.toEpochMilli(), CurrentQuality.FRESH,
				false, reference, values, Map.of(), new BmsMonitoringSnapshot.AppliedThresholds(0.15, 0.15, 200,
						242, 1.25, 4, 6, 3, 0.3, 40, 70, 40, 2), ActiveCalibrationContext.none(), mode,
				BmsCurrentDirectionMapping.NEGATIVE, active,
				Alarm.LOW_SOC.mask() | Alarm.SENSOR_MODULE_FAILURE.mask(),
				List.of(new BmsMonitoringSnapshot.AlarmDetail(Alarm.LOW_SOC, null, 69.0, 70.0, "low")), null);
	}

	private static int s32(Map<Integer, Integer> registers, int address) {
		return (registers.get(address) << 16) | registers.get(address + 1);
	}

	private static int u32(Map<Integer, Integer> registers, int address) {
		return (registers.get(address) << 16) | registers.get(address + 1);
	}
}
