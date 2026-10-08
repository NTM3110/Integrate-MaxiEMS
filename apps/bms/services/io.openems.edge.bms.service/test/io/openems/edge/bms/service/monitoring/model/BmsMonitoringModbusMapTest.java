package io.openems.edge.bms.service.monitoring.model;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.EnumMap;
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
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringModbusMap.RegisterValueType;

public class BmsMonitoringModbusMapTest {

	@Test
	public void shouldExposeTheCompactV3RegisterCatalog() {
		assertEquals(3, BmsMonitoringModbusMap.CONTRACT_VERSION);
		assertEquals(36, BmsMonitoringModbusMap.LAST_RELATIVE_ADDRESS);
		assertArrayEquals(new int[] { 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31, 33 },
				BmsMonitoringModbusMap.measurementRegisters().stream().mapToInt(value -> value.address()).toArray());
		assertArrayEquals(new int[] { 100, 100, 100, 1, 100, 1000, 1000, 1000, 1, 1, 10, 10, 10, 100,
				100, 100, 100 }, BmsMonitoringModbusMap.measurementRegisters().stream()
						.mapToInt(value -> value.scale()).toArray());
		assertArrayEquals(new String[] { "V", "V", "A", "W", "A", "V", "V", "V", "uOhm", "uOhm",
				"degC", "degC", "degC", "%", "h", "%", "%" }, BmsMonitoringModbusMap.measurementRegisters()
						.stream().map(value -> value.unit()).toArray(String[]::new));
		assertArrayEquals(new String[] { "Battery Voltage", "Midpoint Voltage", "Battery Current",
				"Battery Power", "Float Current", "Min Cell Voltage", "Max Cell Voltage",
				"Average Cell Voltage", "Max Cell Resistance", "Average Cell Resistance",
				"Ambient Temperature", "Max Cell Temperature", "Average Cell Temperature", "Battery SOC",
				"Remaining Discharge Time", "Battery SOH", "Minimum Cell SOH" },
				BmsMonitoringModbusMap.measurementRegisters().stream().map(value -> value.label())
						.toArray(String[]::new));
		assertEquals(RegisterValueType.SIGNED_32, BmsMonitoringModbusMap.measurementRegisters().get(0).type());
		assertEquals(35, BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS);
		assertEquals(36, BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS);
		assertArrayEquals(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 },
				BmsMonitoringModbusMap.alarmBits().stream().mapToInt(value -> value.bit()).toArray());
		assertArrayEquals(Alarm.values(), BmsMonitoringModbusMap.alarmBits().stream()
				.map(value -> value.alarm()).toArray(Alarm[]::new));
		assertArrayEquals(new String[] { "Low Cell Voltage Alarm", "High Cell Voltage Alarm",
				"Battery Voltage Low", "Battery Voltage High", "Cell Resistance Alarm",
				"Cell Temperature Alarm", "Float Charge Overcurrent Alarm", "Charge Overcurrent Alarm",
				"Low SOC Alarm", "High Ambient Temperature", "Midpoint Voltage Alarm", "Sensor/Module Failure" },
				BmsMonitoringModbusMap.alarmBits().stream().map(value -> value.label()).toArray(String[]::new));
		assertEquals(RegisterValueType.BITMASK_16, BmsMonitoringModbusMap.alarmBits().get(0).type());
	}

	@Test
	public void shouldExposeSignedMeasurementsAndStableAlarmMasks() {
		var generation = UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000");
		var frame = frame(generation);
		var registers = BmsMonitoringModbusMap.registers(frame);

		assertEquals(36, registers.size());
		var expectedMeasurements = new int[] { 25_325, 12_660, -1_575, -3_989, 15, 2_291, 2_309,
				2_302, 1_901, 1_800, 251, 260, 254, 9_995, 1_025, 8_756, 8_001 };
		for (var index = 0; index < expectedMeasurements.length; index++) {
			assertEquals("measurement " + index, expectedMeasurements[index],
					s32(registers, BmsMonitoringModbusMap.measurementRegisters().get(index).address()));
		}
		assertEquals(Alarm.LOW_SOC.mask() | Alarm.SENSOR_MODULE_FAILURE.mask(),
				registers.get(BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS).intValue());
		assertEquals(0x0fff,
				registers.get(BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS).intValue());
		assertEquals(0, registers.get(BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS) & 0xf000);
		assertEquals(0, registers.get(BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS) & 0xf000);
	}

	@Test
	public void shouldUseExplicitSentinelWithoutTurningUnavailableIntoZero() {
		var missing = BmsMonitoringModbusMap.registers(null);
		assertEquals(36, missing.size());
		assertEquals(BmsMonitoringModbusMap.UNAVAILABLE_S32, s32(missing, 1));
		assertEquals(0, missing.get(BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS).intValue());
		assertEquals(0, missing.get(BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS).intValue());

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
		assertEquals(BmsMonitoringModbusMap.UNAVAILABLE_S32, s32(registers, 3));
		assertEquals(BmsMonitoringModbusMap.UNAVAILABLE_S32, s32(registers, 29));
	}

	@Test
	public void unavailableReferenceAndStaleConditionsNeverLookCleared() {
		var base = frame(UUID.randomUUID());
		var noReference = qualityFrame(base, false, Map.of(
				Alarm.CELL_RESISTANCE, AlarmEvaluation.UNAVAILABLE,
				Alarm.FLOAT_CHARGE_OVERCURRENT, AlarmEvaluation.UNAVAILABLE));
		var registers = BmsMonitoringModbusMap.registers(noReference);
		assertEquals(25_325, s32(registers, 1));
		assertEquals(0x0900, registers.get(BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS).intValue());
		assertEquals(0x0faf, registers.get(BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS).intValue());
		var stale = qualityFrame(base, true, Map.of(Alarm.LOW_SOC, AlarmEvaluation.UNAVAILABLE));
		registers = BmsMonitoringModbusMap.registers(stale);
		assertEquals(0x0800, registers.get(BmsMonitoringModbusMap.ALARM_ACTIVE_MASK_ADDRESS).intValue());
		assertEquals(0x0eff, registers.get(BmsMonitoringModbusMap.ALARM_VALIDITY_MASK_ADDRESS).intValue());
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
		var allEvaluations = new EnumMap<Alarm, AlarmEvaluation>(Alarm.class);
		for (var alarm : Alarm.values()) allEvaluations.put(alarm, AlarmEvaluation.AVAILABLE);
		allEvaluations.putAll(evaluations);
		return new BmsMonitoringSnapshot(base.stringKey(), base.snapshotGeneration(), base.snapshotSequence(),
				base.capturedAt(), base.currentMeasuredAt(), base.currentQuality(), stale, null, base.string(),
				base.cells(), base.thresholds(), base.activeCalibration(), base.operatingMode(),
				base.currentDirectionMapping(), base.activeAlarms(), base.alarmMask(), base.alarmDetails(),
				stale ? "stale" : "reference unavailable", base.calculationSnapshot(), base.publicationGeneration(),
				base.publicationRevision() + 1, allEvaluations);
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

}
