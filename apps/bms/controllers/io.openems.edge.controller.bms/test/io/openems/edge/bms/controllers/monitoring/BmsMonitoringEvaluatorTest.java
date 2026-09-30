package io.openems.edge.bms.controllers.monitoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmEvaluation;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

public class BmsMonitoringEvaluatorTest {

	@Test
	public void noReferenceRetainsFullTelemetryAndIndependentAlarms() {
		long now = 1_000_000L;
		var source = snapshot(now, 253.0, 0.1, 2_300, 80.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", source, null, ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(source, OperatingMode.IDLE), now);
		assertSame(source, frame.calculationSnapshot());
		assertFalse(frame.referenceAvailable());
		assertNull(frame.reference());
		assertNull(frame.qualityWarning());
		assertNull(frame.thresholds().floatChargeOvercurrentA());
		assertNull(frame.thresholds().chargeOvercurrentA());
		assertEquals(253.0, frame.string().batteryVoltageV(), 0.0);
		assertTrue(frame.activeAlarms().contains(Alarm.BATTERY_VOLTAGE_HIGH));
		assertTrue(frame.activeAlarms().contains(Alarm.LOW_SOC));
		assertFalse(frame.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.CELL_RESISTANCE));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.FLOAT_CHARGE_OVERCURRENT));
		assertEquals(AlarmEvaluation.AVAILABLE, frame.alarmEvaluations().get(Alarm.BATTERY_VOLTAGE_HIGH));
	}

	@Test
	public void activeProcessLimitCanEvaluateChargeCurrentWithoutBaseline() {
		long now = 1_000_000L;
		var source = snapshot(now, 253.0, 50.0, 1_800, 95.0);
		var context = new ActiveCalibrationContext(42L, 2L, 3L, "INITIAL_BASELINE", "RECHARGE",
				"RECOVERY_CHARGE_TO_VBOOST", 40.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", source, null, context,
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(source, OperatingMode.CHARGING), now);
		assertEquals(40.0, frame.thresholds().chargeOvercurrentA(), 0.0);
		assertTrue(frame.activeAlarms().contains(Alarm.CHARGE_OVERCURRENT));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.FLOAT_CHARGE_OVERCURRENT));
	}

	@Test
	public void derivesMetricsAndBaselineAlarmsFromOneSnapshot() {
		long now = 1_000_000L;
		var snapshot = snapshot(now, 210.0, 50.0, 2_300, 80.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(snapshot, OperatingMode.FLOAT), now);

		assertEquals(-10_500.0, frame.string().batteryPowerW(), 0.0);
		assertEquals(90.0, frame.string().batterySohPct(), 0.0);
		assertEquals(80.0, frame.string().minimumCellSohPct(), 0.0);
		assertTrue(frame.activeAlarms().contains(Alarm.CELL_RESISTANCE));
		assertTrue(frame.activeAlarms().contains(Alarm.FLOAT_CHARGE_OVERCURRENT));
		assertTrue(frame.activeAlarms().contains(Alarm.CHARGE_OVERCURRENT));
		assertEquals(50.0, frame.string().floatCurrentA(), 0.0);
		assertEquals(0.3, frame.thresholds().floatChargeOvercurrentA(), 0.000_001);
		assertEquals(2250.0, frame.cells().get(1).resistanceAlarmThresholdUohm(), 0.0);
		assertEquals(BmsCurrentDirectionMapping.NEGATIVE, frame.currentDirectionMapping());
		assertTrue((frame.alarmMask() & Alarm.CELL_RESISTANCE.mask()) != 0);
		assertFalse(frame.stale());
	}

	@Test
	public void copiesEstimatorProvenanceWithoutRecomputingTheCalculationGeneration() {
		long now = 1_000_000L;
		var source = snapshot(now, 210.0, 0.1, 1_800, 95.0);
		var reference = new EstimatorReferenceIdentity(42L, 3L, 2L, 0, "hash");
		var soc = new EstimatorProvenance("COULOMB_COUNT", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", reference);
		var soh = new EstimatorProvenance("AUTHORITATIVE_BASELINE", "PUBLISHED_BASELINE",
				EstimatorQuality.AUTHORITATIVE, "published", reference);
		var cell = source.cells().get(1);
		var enrichedCell = new BmsCalculationSnapshot.Cell(cell.cellId(), cell.voltage(), cell.temperature(),
				cell.internalResistance(), cell.soc(), cell.soh(), cell.ir(), cell.availabilityWarning(), soc, soh);
		var enriched = new BmsCalculationSnapshot(source.generation(), source.sequence(), source.capturedAt(),
				source.configuredCellCount(), source.ambientTemperature(), source.stringCurrent(), source.currentMeasuredAt(),
				source.currentQuality(), source.currentWarning(), source.stringSoc(), 79.0, source.stringVoltage(),
				source.midpointVoltage(), source.averageVoltage(), source.averageTemperature(), source.averageResistance(),
				source.maxVoltage(), source.maxVoltageCellIndex(), source.minVoltage(), source.minVoltageCellIndex(),
				source.maxTemperature(), source.maxTemperatureCellIndex(), source.minTemperature(),
				source.minTemperatureCellIndex(), source.maxResistance(), source.maxResistanceCellIndex(),
				source.minResistance(), source.minResistanceCellIndex(), Map.of(1, enrichedCell, 2, source.cells().get(2)),
				source.stringWarning(), soc, soh);
		var frame = BmsMonitoringEvaluator.evaluate("str1", enriched, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(enriched, OperatingMode.IDLE), now);
		assertSame(enriched, frame.calculationSnapshot());
		assertSame(soc, frame.socEstimator());
		assertSame(soh, frame.sohEstimator());
		assertSame(soc, frame.cells().get(1).socEstimator());
		assertSame(soh, frame.cells().get(1).sohEstimator());
		assertEquals(79.0, frame.string().batterySohPct(), 0.0);
	}

	@Test
	public void unavailableSocProvenanceSuspendsLowSocInsteadOfClearingIt() {
		long now = 1_000_000L;
		var source = snapshot(now, 210.0, 0.1, 1_800, 95.0);
		var unavailable = new EstimatorProvenance("UNAVAILABLE", "UNAVAILABLE", EstimatorQuality.UNAVAILABLE,
				"reference unavailable", null);
		var enriched = new BmsCalculationSnapshot(source.generation(), source.sequence(), source.capturedAt(),
				source.configuredCellCount(), source.ambientTemperature(), source.stringCurrent(), source.currentMeasuredAt(),
				source.currentQuality(), source.currentWarning(), 10.0, source.stringSoh(), source.stringVoltage(),
				source.midpointVoltage(), source.averageVoltage(), source.averageTemperature(), source.averageResistance(),
				source.maxVoltage(), source.maxVoltageCellIndex(), source.minVoltage(), source.minVoltageCellIndex(),
				source.maxTemperature(), source.maxTemperatureCellIndex(), source.minTemperature(),
				source.minTemperatureCellIndex(), source.maxResistance(), source.maxResistanceCellIndex(),
				source.minResistance(), source.minResistanceCellIndex(), source.cells(), source.stringWarning(), unavailable,
				source.sohEstimator());
		var frame = BmsMonitoringEvaluator.evaluate("str1", enriched, null, ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(enriched, OperatingMode.IDLE), now);
		assertFalse(frame.activeAlarms().contains(Alarm.LOW_SOC));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.LOW_SOC));
	}

	@Test
	public void unavailableSocProvenanceDoesNotPublishRemainingDischargeTime() {
		long now = 1_000_000L;
		var source = snapshot(now, 210.0, -20.0, 1_800, 95.0);
		var unavailable = new EstimatorProvenance("UNAVAILABLE", "UNAVAILABLE", EstimatorQuality.UNAVAILABLE,
				"capacity unavailable", null);
		var enriched = new BmsCalculationSnapshot(source.generation(), source.sequence(), source.capturedAt(),
				source.configuredCellCount(), source.ambientTemperature(), source.stringCurrent(), source.currentMeasuredAt(),
				source.currentQuality(), source.currentWarning(), 50.0, source.stringSoh(), source.stringVoltage(),
				source.midpointVoltage(), source.averageVoltage(), source.averageTemperature(), source.averageResistance(),
				source.maxVoltage(), source.maxVoltageCellIndex(), source.minVoltage(), source.minVoltageCellIndex(),
				source.maxTemperature(), source.maxTemperatureCellIndex(), source.minTemperature(),
				source.minTemperatureCellIndex(), source.maxResistance(), source.maxResistanceCellIndex(),
				source.minResistance(), source.minResistanceCellIndex(), source.cells(), source.stringWarning(), unavailable,
				source.sohEstimator());
		var frame = BmsMonitoringEvaluator.evaluate("str1", enriched, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(enriched, OperatingMode.DISCHARGING), now);

		assertNull(frame.string().remainingDischargeHours());
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.LOW_SOC));
	}

	@Test
	public void staleAndQualityWarningsUseSensorFailureBit() {
		long capturedAt = 1_000_000L;
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot(capturedAt, 210.0, 0.1, 1_800, 95.0),
				baseline(), ActiveCalibrationContext.none(), BmsMonitoringPolicy.defaults(),
				BmsCurrentDirectionMapping.NEGATIVE,
				ModeEvidence.unknown(UUID.randomUUID(), 7, Instant.ofEpochMilli(capturedAt), "test"),
				capturedAt + 61_000L);
		assertTrue(frame.stale());
		assertTrue(frame.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.BATTERY_VOLTAGE_HIGH));
		assertEquals(1, frame.activeAlarms().size());
	}

	@Test
	public void freshRetainedCurrentDoesNotCreateSensorFailure() {
		long now = 1_000_000L;
		var source = snapshot(now, 210.0, 0.1, 1_800, 95.0);
		var retained = new BmsCalculationSnapshot(source.generation(), source.sequence(), source.capturedAt(),
				source.configuredCellCount(), source.ambientTemperature(), source.stringCurrent(),
				source.currentMeasuredAt(), CurrentQuality.RETAINED, null, source.stringSoc(), source.stringSoh(),
				source.stringVoltage(), source.midpointVoltage(), source.averageVoltage(), source.averageTemperature(),
				source.averageResistance(), source.maxVoltage(), source.maxVoltageCellIndex(), source.minVoltage(),
				source.minVoltageCellIndex(), source.maxTemperature(), source.maxTemperatureCellIndex(),
				source.minTemperature(), source.minTemperatureCellIndex(), source.maxResistance(),
				source.maxResistanceCellIndex(), source.minResistance(), source.minResistanceCellIndex(),
				source.cells(), null);
		var frame = BmsMonitoringEvaluator.evaluate("str1", retained, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(retained, OperatingMode.IDLE), now);
		assertFalse(frame.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.LOW_SOC));
	}

	@Test
	public void dischargeMagnitudeNeverRaisesChargeAlarms() {
		long now = 1_000_000L;
		var snapshot = snapshot(now, 210.0, -50.0, 1_800, 95.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(snapshot, OperatingMode.DISCHARGING), now);
		assertEquals(50.0, frame.string().batteryCurrentA(), 0.0);
		assertFalse(frame.activeAlarms().contains(Alarm.FLOAT_CHARGE_OVERCURRENT));
		assertFalse(frame.activeAlarms().contains(Alarm.CHARGE_OVERCURRENT));
	}

	@Test
	public void missingConfiguredCellRaisesSensorEvenWithoutCellWarnings() {
		long now = 1_000_000L;
		var source = snapshot(now, 210.0, 0.1, 1_800, 95.0);
		var missing = new BmsCalculationSnapshot(source.generation(), source.sequence(), source.capturedAt(),
				3, source.ambientTemperature(), source.stringCurrent(), source.currentMeasuredAt(), CurrentQuality.FRESH,
				null, source.stringSoc(), source.stringSoh(), source.stringVoltage(), source.midpointVoltage(),
				source.averageVoltage(), source.averageTemperature(), source.averageResistance(), source.maxVoltage(),
				source.maxVoltageCellIndex(), source.minVoltage(), source.minVoltageCellIndex(), source.maxTemperature(),
				source.maxTemperatureCellIndex(), source.minTemperature(), source.minTemperatureCellIndex(),
				source.maxResistance(), source.maxResistanceCellIndex(), source.minResistance(),
				source.minResistanceCellIndex(), source.cells(), null);
		var frame = BmsMonitoringEvaluator.evaluate("str1", missing, null, ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE, mode(missing, OperatingMode.IDLE), now);
		assertTrue(frame.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(AlarmEvaluation.UNAVAILABLE, frame.alarmEvaluations().get(Alarm.CELL_RESISTANCE));
		assertEquals(1, frame.activeAlarms().size());
	}

	@Test
	public void unknownModeSuppressesBothChargeAlarms() {
		long now = 1_000_000L;
		var snapshot = snapshot(now, 210.0, 50.0, 1_800, 95.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				ModeEvidence.unknown(snapshot.generation(), snapshot.sequence(), Instant.ofEpochMilli(now), "restart"), now);
		assertFalse(frame.activeAlarms().contains(Alarm.FLOAT_CHARGE_OVERCURRENT));
		assertFalse(frame.activeAlarms().contains(Alarm.CHARGE_OVERCURRENT));
	}

	@Test
	public void chargingModeRaisesOnlyGenericChargeAlarm() {
		long now = 1_000_000L;
		var snapshot = snapshot(now, 250.0, 50.0, 1_800, 95.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(snapshot, OperatingMode.CHARGING), now);
		assertFalse(frame.activeAlarms().contains(Alarm.FLOAT_CHARGE_OVERCURRENT));
		assertTrue(frame.activeAlarms().contains(Alarm.CHARGE_OVERCURRENT));
	}

	@Test
	public void floatModeKeepsTwoIndependentAlarmDetails() {
		long now = 1_000_000L;
		var snapshot = snapshot(now, 253.0, 50.0, 1_800, 95.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.NEGATIVE,
				mode(snapshot, OperatingMode.FLOAT), now);
		assertEquals(1, frame.alarmDetails().stream()
				.filter(value -> value.alarm() == Alarm.FLOAT_CHARGE_OVERCURRENT).count());
		assertEquals(1, frame.alarmDetails().stream()
				.filter(value -> value.alarm() == Alarm.CHARGE_OVERCURRENT).count());
	}

	@Test
	public void positiveDischargeMappingAlsoPreservesChargeSemantics() {
		long now = 1_000_000L;
		var snapshot = snapshot(now, 250.0, -50.0, 1_800, 95.0);
		var frame = BmsMonitoringEvaluator.evaluate("str1", snapshot, baseline(), ActiveCalibrationContext.none(),
				BmsMonitoringPolicy.defaults(), BmsCurrentDirectionMapping.POSITIVE,
				mode(snapshot, OperatingMode.CHARGING), now);
		assertEquals(-50.0, frame.string().batteryCurrentA(), 0.0);
		assertTrue(frame.activeAlarms().contains(Alarm.CHARGE_OVERCURRENT));
	}

	private static ModeEvidence mode(BmsCalculationSnapshot snapshot, OperatingMode mode) {
		return new ModeEvidence(mode, ModeSource.TELEMETRY_INFERENCE, Instant.ofEpochMilli(snapshot.capturedAt()),
				snapshot.generation(), snapshot.sequence(), ModeQuality.VALID, "test");
	}

	static BmsCalculationSnapshot snapshot(long capturedAt, double voltage, double current,
			int firstResistance, double firstSoh) {
		var cells = Map.of(
				1, new BmsCalculationSnapshot.Cell(1, 1.8, 25.0, firstResistance, 60.0, firstSoh, 1_800.0, null),
				2, new BmsCalculationSnapshot.Cell(2, 1.9, 25.0, 1_800, 61.0, 95.0, 1_800.0, null));
		return new BmsCalculationSnapshot(UUID.randomUUID(), 7, capturedAt, 2, 25.0, current, capturedAt,
				CurrentQuality.FRESH, null, 60.5, 90.0, voltage, voltage / 2.0, 1.85, 25.0, 2_050.0,
				1.9, 2, 1.8, 1, 25.0, 1, 25.0, 1, firstResistance, 1, 1_800, 2, cells, null);
	}

	static InitialBaselineReferenceDataset baseline() {
		var cells = List.of(
				new InitialBaselineReferenceDataset.CellMeasurement(1, 2.3, 25.0, 1_800, 100.0, 100.0, "VALID", null),
				new InitialBaselineReferenceDataset.CellMeasurement(2, 2.3, 25.0, 1_800, 100.0, 100.0, "VALID", null));
		var snapshot = new InitialBaselineReferenceDataset.PersistedSnapshot(1, 2, 0, UUID.randomUUID(), 1,
				Instant.EPOCH, 0.1, 0.1, 253.0, 126.5, 2.3, 25.0, 25.0, cells);
		var evidence = Map.of("CHARGER_OUTPUT_CURRENT",
				new InitialBaselineReferenceDataset.Evidence(1, "CHARGER_OUTPUT_CURRENT", "PASS", 0.1, "A", 1L,
						Instant.EPOCH));
		return new InitialBaselineReferenceDataset(42, UUID.randomUUID(), "str1", 2, 200.0, Instant.EPOCH,
				Instant.EPOCH, UUID.randomUUID(), new InitialBaselineReferenceDataset.CurveVersion(3, 2, 0, 1, 200.0,
						180.0, 190.0, 2, 0, "hash", Instant.EPOCH), snapshot, snapshot, evidence, List.of(), List.of());
	}
}
