package io.openems.edge.bms.controllers.monitoring;

import java.time.Instant;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmDetail;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmEvaluation;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.CellMeasurementQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

/** Pure function that derives the complete monitoring frame from one snapshot. */
public final class BmsMonitoringEvaluator {

	private BmsMonitoringEvaluator() {
	}

	public static BmsMonitoringSnapshot evaluate(String stringKey, BmsCalculationSnapshot snapshot,
			InitialBaselineReferenceDataset baseline, ActiveCalibrationContext context,
			BmsMonitoringPolicy policy, BmsCurrentDirectionMapping currentDirectionMapping,
			ModeEvidence mode, long nowMillis) {
		return evaluate(stringKey, snapshot, baseline, context, policy, currentDirectionMapping, mode, nowMillis, false);
	}

	static BmsMonitoringSnapshot evaluate(String stringKey, BmsCalculationSnapshot snapshot,
			InitialBaselineReferenceDataset baseline, ActiveCalibrationContext context,
			BmsMonitoringPolicy policy, BmsCurrentDirectionMapping currentDirectionMapping,
			ModeEvidence mode, long nowMillis, boolean sourceUnavailable) {
		if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
		if (context == null) context = ActiveCalibrationContext.none();
		if (policy == null) policy = BmsMonitoringPolicy.defaults();
		if (currentDirectionMapping == null) throw new IllegalArgumentException("current direction mapping is required");
		if (mode == null) throw new IllegalArgumentException("operating mode evidence is required");

		var alarms = EnumSet.noneOf(Alarm.class);
		var details = new ArrayList<AlarmDetail>();
		var cells = new LinkedHashMap<Integer, BmsMonitoringSnapshot.CellMeasurements>();
		long freshnessMillis = policy.snapshotFreshnessSeconds() * 1_000L;
		boolean completeCells = snapshot.cells().size() == snapshot.configuredCellCount()
				&& !snapshot.cells().isEmpty()
				&& java.util.stream.IntStream.rangeClosed(1, snapshot.configuredCellCount())
						.allMatch(snapshot.cells()::containsKey);
		Double minimumSoh = null;
		for (var entry : snapshot.cells().entrySet()) {
			var value = entry.getValue();
			Long measuredAt = value.measuredAt() == null
					&& value.quality() == BmsCalculationSnapshot.CellQuality.FRESH
							? Long.valueOf(snapshot.capturedAt()) : value.measuredAt();
			Long ageMs = measuredAt == null ? null : Math.max(0L, nowMillis - measuredAt);
			var quality = cellQuality(value.quality(), ageMs, freshnessMillis);
			var expose = quality == CellMeasurementQuality.FRESH || quality == CellMeasurementQuality.RETAINED;
			Double resistanceThreshold = null;
			if (baseline != null && entry.getKey() > 0 && entry.getKey() <= baseline.recoveryFloatSnapshot().cells().size()) {
				var resistance = baseline.recoveryFloatSnapshot().cells().get(entry.getKey() - 1).resistanceUohm();
				if (finite(resistance)) resistanceThreshold = resistance * policy.resistanceBaselineMultiplier();
			}
			cells.put(entry.getKey(), new BmsMonitoringSnapshot.CellMeasurements(entry.getKey(),
					expose ? value.voltage() : null,
					expose && value.internalResistance() != null ? value.internalResistance().doubleValue() : null,
					expose ? value.temperature() : null, expose ? value.soc() : null, expose ? value.soh() : null,
					value.availabilityWarning(), resistanceThreshold, expose ? value.socEstimator() : null,
					expose ? value.sohEstimator() : null, measuredAt, ageMs, quality));
			if (expose && finite(value.soh())) {
				minimumSoh = minimumSoh == null ? value.soh() : Math.min(minimumSoh, value.soh());
			}
			if (value.availabilityWarning() != null
					|| quality == CellMeasurementQuality.STALE
					|| quality == CellMeasurementQuality.UNAVAILABLE) {
				add(alarms, details, Alarm.SENSOR_MODULE_FAILURE, entry.getKey(), null, null,
						value.availabilityWarning() == null ? "CELL_DATA_" + quality.name()
								: value.availabilityWarning());
			}
		}

		var allVoltages = completeCells && cells.values().stream().allMatch(cell -> finite(cell.voltageV()));
		var allTemperatures = completeCells && cells.values().stream().allMatch(cell -> finite(cell.temperatureC()));
		var allResistances = completeCells && cells.values().stream().allMatch(cell -> finite(cell.resistanceUohm()));
		var averageVoltage = allVoltages ? snapshot.averageVoltage() : null;
		var averageTemperature = allTemperatures ? snapshot.averageTemperature() : null;
		var averageResistance = allResistances ? snapshot.averageResistance() : null;
		var ambient = snapshot.ambientTemperature();
		for (var entry : cells.entrySet()) {
			var cell = entry.getValue();
			if (finite(cell.voltageV()) && finite(averageVoltage)) {
				double low = averageVoltage * (1.0 - policy.cellVoltageDeviationFraction());
				double high = averageVoltage * (1.0 + policy.cellVoltageDeviationFraction());
				if (cell.voltageV() < low) add(alarms, details, Alarm.LOW_CELL_VOLTAGE, entry.getKey(), cell.voltageV(), low,
						"Cell voltage is below the live-average deviation limit");
				if (cell.voltageV() > high) add(alarms, details, Alarm.HIGH_CELL_VOLTAGE, entry.getKey(), cell.voltageV(), high,
						"Cell voltage is above the live-average deviation limit");
			}
			var resistanceThreshold = cells.get(entry.getKey()).resistanceAlarmThresholdUohm();
			if (finite(cell.resistanceUohm()) && resistanceThreshold != null && cell.resistanceUohm() > resistanceThreshold) {
				add(alarms, details, Alarm.CELL_RESISTANCE, entry.getKey(), cell.resistanceUohm(),
						resistanceThreshold, "Cell resistance exceeds its Initial Baseline");
			}
			if (finite(cell.temperatureC())) {
				if (finite(averageTemperature)
						&& cell.temperatureC() > averageTemperature + policy.cellTemperatureAboveAverageC()) {
					add(alarms, details, Alarm.CELL_TEMPERATURE, entry.getKey(), cell.temperatureC(),
							averageTemperature + policy.cellTemperatureAboveAverageC(),
							"Cell temperature exceeds live average");
				}
				if (finite(ambient) && cell.temperatureC() > ambient + policy.cellTemperatureAboveAmbientC()) {
					add(alarms, details, Alarm.CELL_TEMPERATURE, entry.getKey(), cell.temperatureC(),
							ambient + policy.cellTemperatureAboveAmbientC(),
							"Cell temperature exceeds ambient");
				}
			}
		}

		/*
		 * String voltage and midpoint voltage are TC measurements. A stale TA cell
		 * invalidates cell-derived aggregates and alarms, but must not hide otherwise
		 * fresh string-level telemetry or suspend its independent alarms.
		 */
		var voltage = snapshot.stringVoltage();
		var midpointVoltage = snapshot.midpointVoltage();
		boolean allCellSoc = completeCells && cells.values().stream().allMatch(cell -> finite(cell.socPct()));
		boolean allCellSoh = completeCells && cells.values().stream().allMatch(cell -> finite(cell.sohPct()));
		boolean socAvailable = allCellSoc && finite(snapshot.stringSoc())
				&& (snapshot.socEstimator() == null
						|| snapshot.socEstimator().quality() != BmsCalculationSnapshot.EstimatorQuality.UNAVAILABLE);
		if (finite(voltage) && voltage < policy.batteryVoltageLowV()) add(alarms, details,
				Alarm.BATTERY_VOLTAGE_LOW, null, voltage, policy.batteryVoltageLowV(), "Battery voltage is low");
		if (finite(voltage) && voltage > policy.batteryVoltageHighV()) add(alarms, details,
				Alarm.BATTERY_VOLTAGE_HIGH, null, voltage, policy.batteryVoltageHighV(), "Battery voltage is high");
		if (socAvailable && snapshot.stringSoc() < policy.lowSocPct()) add(alarms, details,
				Alarm.LOW_SOC, null, snapshot.stringSoc(), policy.lowSocPct(), "Battery SoC is low");
		if (finite(ambient) && ambient > policy.highAmbientTemperatureC()) add(alarms, details,
				Alarm.HIGH_AMBIENT_TEMPERATURE, null, ambient, policy.highAmbientTemperatureC(),
				"Ambient temperature is high");
		if (finite(voltage) && finite(midpointVoltage)) {
			double imbalance = Math.abs(midpointVoltage - voltage / 2.0);
			if (imbalance > policy.midpointImbalanceV()) add(alarms, details, Alarm.MIDPOINT_VOLTAGE, null,
					imbalance, policy.midpointImbalanceV(), "Midpoint half-string imbalance is high");
		}

		var floatEvidence = baseline == null ? null : baseline.evidence().get("CHARGER_OUTPUT_CURRENT");
		Double floatReference = floatEvidence == null ? null : floatEvidence.numericValue();
		Double floatThreshold = finite(floatReference) && floatReference >= 0
				? floatReference * policy.floatCurrentBaselineMultiplier() : null;
		Double chargeThreshold = context.chargeCurrentLimitA() == null
				? baseline == null ? null : baseline.ratedCapacityAh() * policy.chargeOvercurrentCapacityFraction()
				: context.chargeCurrentLimitA();
		Double domainCurrent = finite(snapshot.stringCurrent())
				? currentDirectionMapping.toDomainCurrent(snapshot.stringCurrent()) : null;
		Double chargeCurrent = domainCurrent != null && domainCurrent < 0 ? -domainCurrent : null;
		boolean validMode = mode.quality() == BmsMonitoringSnapshot.ModeQuality.VALID;
		boolean floatMode = validMode && mode.mode() == BmsMonitoringSnapshot.OperatingMode.FLOAT;
		boolean chargeMode = validMode && (mode.mode() == BmsMonitoringSnapshot.OperatingMode.CHARGING || floatMode);
		if (floatMode && chargeCurrent != null && floatThreshold != null && chargeCurrent > floatThreshold) add(alarms, details,
				Alarm.FLOAT_CHARGE_OVERCURRENT, null, chargeCurrent, floatThreshold,
				"Float current exceeds Initial Baseline multiplier");
		if (chargeMode && chargeCurrent != null && chargeThreshold != null && chargeCurrent > chargeThreshold) add(alarms, details,
				Alarm.CHARGE_OVERCURRENT, null, chargeCurrent, chargeThreshold, "Charge current exceeds applied limit");

		boolean snapshotStale = sourceUnavailable || nowMillis - snapshot.capturedAt() >= freshnessMillis;
		boolean currentStale = snapshot.currentMeasuredAt() == null
				|| nowMillis - snapshot.currentMeasuredAt() >= freshnessMillis;
		boolean stale = snapshotStale || currentStale;
		if (stale || snapshot.stringWarning() != null || snapshot.currentWarning() != null
				|| !completeCells || snapshot.currentQuality() == BmsCalculationSnapshot.CurrentQuality.MISSING
				|| snapshot.currentQuality() == BmsCalculationSnapshot.CurrentQuality.COMMUNICATION_FAILED
				|| snapshot.currentQuality() == BmsCalculationSnapshot.CurrentQuality.TIMESTAMP_MISSING) {
			add(alarms, details, Alarm.SENSOR_MODULE_FAILURE, null, null, null,
					stale ? "Monitoring snapshot is stale" : !completeCells ? "Configured cell coverage is incomplete"
							: "Snapshot quality warning");
		}
		var evaluations = new EnumMap<Alarm, AlarmEvaluation>(Alarm.class);
		for (var alarm : Alarm.values()) evaluations.put(alarm, AlarmEvaluation.AVAILABLE);
		boolean resistanceAvailable = baseline != null && completeCells
				&& allResistances
				&& baseline.recoveryFloatSnapshot().cells().size() == snapshot.configuredCellCount()
				&& baseline.recoveryFloatSnapshot().cells().stream().allMatch(cell -> finite(cell.resistanceUohm()));
		unavailableUnless(evaluations, Alarm.LOW_CELL_VOLTAGE, allVoltages && finite(averageVoltage));
		unavailableUnless(evaluations, Alarm.HIGH_CELL_VOLTAGE, allVoltages && finite(averageVoltage));
		unavailableUnless(evaluations, Alarm.BATTERY_VOLTAGE_LOW, finite(voltage));
		unavailableUnless(evaluations, Alarm.BATTERY_VOLTAGE_HIGH, finite(voltage));
		unavailableUnless(evaluations, Alarm.CELL_RESISTANCE, resistanceAvailable);
		unavailableUnless(evaluations, Alarm.CELL_TEMPERATURE,
				allTemperatures && finite(averageTemperature) && finite(ambient));
		unavailableUnless(evaluations, Alarm.FLOAT_CHARGE_OVERCURRENT, validMode && finite(floatThreshold));
		unavailableUnless(evaluations, Alarm.CHARGE_OVERCURRENT, validMode && finite(chargeThreshold));
		unavailableUnless(evaluations, Alarm.LOW_SOC, socAvailable);
		unavailableUnless(evaluations, Alarm.HIGH_AMBIENT_TEMPERATURE, finite(ambient));
		unavailableUnless(evaluations, Alarm.MIDPOINT_VOLTAGE, finite(voltage) && finite(midpointVoltage));
		if (stale || !completeCells || snapshot.currentQuality() != BmsCalculationSnapshot.CurrentQuality.FRESH) {
			for (var alarm : Alarm.values()) {
				if (alarm != Alarm.SENSOR_MODULE_FAILURE) evaluations.put(alarm, AlarmEvaluation.UNAVAILABLE);
			}
		}
		alarms.removeIf(alarm -> evaluations.get(alarm) == AlarmEvaluation.UNAVAILABLE);
		details.removeIf(detail -> evaluations.get(detail.alarm()) == AlarmEvaluation.UNAVAILABLE);
		int alarmMask = alarms.stream().mapToInt(Alarm::mask).reduce(0, (left, right) -> left | right);
		Double current = domainCurrent;
		Double power = finite(voltage) && finite(current) ? voltage * current : null;
		Double remainingDischargeHours = baseline != null && socAvailable && validMode
				&& mode.mode() == BmsMonitoringSnapshot.OperatingMode.DISCHARGING
				&& current != null && current > 0 && finite(snapshot.stringSoc())
				? baseline.curve().stringSafeUsableCapacityAh() * snapshot.stringSoc() / 100.0 / current
				: null;
		var thresholds = new BmsMonitoringSnapshot.AppliedThresholds(policy.cellVoltageDeviationFraction(),
				policy.cellVoltageDeviationFraction(), policy.batteryVoltageLowV(), policy.batteryVoltageHighV(),
				policy.resistanceBaselineMultiplier(), policy.cellTemperatureAboveAverageC(),
				policy.cellTemperatureAboveAmbientC(), policy.floatCurrentBaselineMultiplier(), floatThreshold, chargeThreshold,
				policy.lowSocPct(), policy.highAmbientTemperatureC(), policy.midpointImbalanceV());
		var string = new BmsMonitoringSnapshot.StringMeasurements(voltage, midpointVoltage, current, power,
				floatMode ? chargeCurrent : null, allVoltages ? snapshot.minVoltage() : null,
				allVoltages ? snapshot.maxVoltage() : null, averageVoltage,
				allResistances ? snapshot.maxResistance() : null, averageResistance, ambient,
				allTemperatures ? snapshot.maxTemperature() : null, averageTemperature,
				socAvailable ? snapshot.stringSoc() : null, remainingDischargeHours,
				allCellSoh ? snapshot.stringSoh() : null, allCellSoh ? minimumSoh : null);
		var reference = baseline == null ? null : new BmsMonitoringSnapshot.ReferenceIdentity(baseline.calibrationEventId(), baseline.curve().id(),
				baseline.curve().calibrationProcessId(), baseline.curve().processAttemptNumber(),
				baseline.recoveryFloatSnapshot().sampleId(), baseline.vocSnapshot().sampleId(),
				baseline.curve().contentHash());
		return new BmsMonitoringSnapshot(stringKey, snapshot.generation(), snapshot.sequence(),
				Instant.ofEpochMilli(snapshot.capturedAt()), snapshot.currentMeasuredAt(), snapshot.currentQuality(), stale,
				reference, string, cells, thresholds, context, mode, currentDirectionMapping, alarms, alarmMask, details,
				snapshot.stringWarning(), snapshot, snapshot.generation(), snapshot.sequence(), evaluations,
				snapshot.socEstimator(), snapshot.sohEstimator());
	}

	private static CellMeasurementQuality cellQuality(BmsCalculationSnapshot.CellQuality quality, Long ageMs,
			long freshnessMillis) {
		if (quality == BmsCalculationSnapshot.CellQuality.UNAVAILABLE || ageMs == null) {
			return CellMeasurementQuality.UNAVAILABLE;
		}
		if (ageMs >= freshnessMillis) return CellMeasurementQuality.STALE;
		return quality == BmsCalculationSnapshot.CellQuality.RETAINED
				? CellMeasurementQuality.RETAINED : CellMeasurementQuality.FRESH;
	}


	private static void unavailableUnless(EnumMap<Alarm, AlarmEvaluation> evaluations, Alarm alarm, boolean available) {
		if (!available) evaluations.put(alarm, AlarmEvaluation.UNAVAILABLE);
	}

	private static boolean finite(Number value) {
		return value != null && Double.isFinite(value.doubleValue());
	}

	private static void add(EnumSet<Alarm> alarms, ArrayList<AlarmDetail> details, Alarm alarm,
			Integer cellIndex, Double observed, Double threshold, String reason) {
		alarms.add(alarm);
		details.add(new AlarmDetail(alarm, cellIndex, observed, threshold, reason));
	}
}
