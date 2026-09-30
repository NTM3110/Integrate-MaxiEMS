package io.openems.edge.bms.service.alarms.implement;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmDetail;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecycleStateMachine.Observation;

public class AlarmConditionResolverTest {

	@Test
	public void unknownModeSuspendsChargeAlarmAndValidModeAppliesHysteresis() {
		var rule = AlarmLifecyclePolicy.defaults().rule(Alarm.CHARGE_OVERCURRENT);
		assertEquals(Observation.SUSPENDED, AlarmConditionResolver.resolve(
				frame(OperatingMode.UNKNOWN, ModeQuality.UNAVAILABLE, -39.0, false, List.of()),
				Alarm.CHARGE_OVERCURRENT, null, 40.0, true, rule).observation());
		assertEquals(Observation.ACTIVE, AlarmConditionResolver.resolve(
				frame(OperatingMode.CHARGING, ModeQuality.VALID, -39.0, false, List.of()),
				Alarm.CHARGE_OVERCURRENT, null, 40.0, true, rule).observation());
		assertEquals(Observation.CLEAR, AlarmConditionResolver.resolve(
				frame(OperatingMode.CHARGING, ModeQuality.VALID, -38.0, false, List.of()),
				Alarm.CHARGE_OVERCURRENT, null, 40.0, true, rule).observation());
	}

	@Test
	public void degradedFrameSuspendsNonSensorClearButRaisesSensorImmediately() {
		var sensor = new AlarmDetail(Alarm.SENSOR_MODULE_FAILURE, null, null, null, "stale");
		var frame = frame(OperatingMode.UNKNOWN, ModeQuality.STALE, null, true, List.of(sensor));
		assertEquals(Observation.SUSPENDED, AlarmConditionResolver.resolve(frame, Alarm.LOW_SOC, null,
				70.0, true, AlarmLifecyclePolicy.defaults().rule(Alarm.LOW_SOC)).observation());
		assertEquals(Observation.ACTIVE, AlarmConditionResolver.resolve(frame, Alarm.SENSOR_MODULE_FAILURE,
				null, null, false, AlarmLifecyclePolicy.defaults().rule(Alarm.SENSOR_MODULE_FAILURE)).observation());
	}

	@Test
	public void qualityAndModeGateOverrideMalformedAlarmDetails() {
		var lowSoc = new AlarmDetail(Alarm.LOW_SOC, null, 60.0, 70.0, "malformed stale detail");
		var charge = new AlarmDetail(Alarm.CHARGE_OVERCURRENT, null, 45.0, 40.0,
				"malformed current detail without mode");
		var stale = frame(OperatingMode.UNKNOWN, ModeQuality.STALE, -45.0, true, List.of(lowSoc, charge));
		assertEquals(Observation.SUSPENDED, AlarmConditionResolver.resolve(stale, Alarm.LOW_SOC, null,
				70.0, false, AlarmLifecyclePolicy.defaults().rule(Alarm.LOW_SOC)).observation());
		assertEquals(Observation.SUSPENDED, AlarmConditionResolver.resolve(stale, Alarm.CHARGE_OVERCURRENT, null,
				40.0, false, AlarmLifecyclePolicy.defaults().rule(Alarm.CHARGE_OVERCURRENT)).observation());

		var unknown = frame(OperatingMode.UNKNOWN, ModeQuality.UNAVAILABLE, -45.0, false, List.of(charge));
		assertEquals(Observation.SUSPENDED, AlarmConditionResolver.resolve(unknown, Alarm.CHARGE_OVERCURRENT,
				null, 40.0, false, AlarmLifecyclePolicy.defaults().rule(Alarm.CHARGE_OVERCURRENT)).observation());
	}

	@Test
	public void degradedQualityRaisesSensorEvenWhenProducerDetailIsMissing() {
		var stale = frame(OperatingMode.UNKNOWN, ModeQuality.STALE, null, true, List.of());
		assertEquals(Observation.ACTIVE, AlarmConditionResolver.resolve(stale, Alarm.SENSOR_MODULE_FAILURE, null,
				null, false, AlarmLifecyclePolicy.defaults().rule(Alarm.SENSOR_MODULE_FAILURE)).observation());
	}

	@Test
	public void currentAppliedThresholdWinsOverPreviousReferenceThreshold() {
		var value = frame(OperatingMode.FLOAT, ModeQuality.VALID, -0.25, false, List.of());
		var result = AlarmConditionResolver.resolve(value, Alarm.FLOAT_CHARGE_OVERCURRENT, null, 0.10, true,
				AlarmLifecyclePolicy.defaults().rule(Alarm.FLOAT_CHARGE_OVERCURRENT));
		assertEquals(0.3, result.threshold(), 0.000001);
		assertEquals(Observation.CLEAR, result.observation());
		var charge = AlarmConditionResolver.resolve(
				frame(OperatingMode.CHARGING, ModeQuality.VALID, -35.0, false, List.of()),
				Alarm.CHARGE_OVERCURRENT, null, 20.0, true,
				AlarmLifecyclePolicy.defaults().rule(Alarm.CHARGE_OVERCURRENT));
		assertEquals(40.0, charge.threshold(), 0.000001);
		assertEquals(Observation.CLEAR, charge.observation());
	}

	private static BmsMonitoringSnapshot frame(OperatingMode mode, ModeQuality quality, Double current,
			boolean stale, List<AlarmDetail> details) {
		var generation = UUID.randomUUID();
		var at = Instant.parse("2026-09-07T00:00:00Z");
		var reference = new BmsMonitoringSnapshot.ReferenceIdentity(42, 2, 100, 0, 200, 201, "hash-42");
		var string = new BmsMonitoringSnapshot.StringMeasurements(253.0, 126.5, current, null, null, 2.2, 2.4,
				2.3, 2_000, 1_800.0, 25.0, 26.0, 25.5, 90.0, null, 88.0, 85.0);
		var thresholds = new BmsMonitoringSnapshot.AppliedThresholds(0.15, 0.15, 200, 242, 1.25, 4, 6, 3,
				0.3, 40, 70, 40, 2);
		var modeEvidence = new ModeEvidence(mode, ModeSource.TELEMETRY_INFERENCE, at, generation, 1, quality,
				"fixture");
		var alarms = details.isEmpty() ? Set.<Alarm>of() : Set.of(Alarm.SENSOR_MODULE_FAILURE);
		return new BmsMonitoringSnapshot("str1", generation, 1, at, at.toEpochMilli(),
				stale ? CurrentQuality.RETAINED : CurrentQuality.FRESH, stale, reference, string, Map.of(), thresholds,
				ActiveCalibrationContext.none(), modeEvidence, BmsCurrentDirectionMapping.NEGATIVE, alarms,
				alarms.stream().mapToInt(Alarm::mask).sum(), details, stale ? "stale" : null);
	}
}
