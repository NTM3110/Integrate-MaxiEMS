package io.openems.edge.bms.service.monitoring.model;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;

import java.time.Instant;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;

/** One atomic monitoring result derived from one calculator snapshot. */
public record BmsMonitoringSnapshot(String stringKey, UUID snapshotGeneration, long snapshotSequence,
		Instant capturedAt, Long currentMeasuredAt, CurrentQuality currentQuality, boolean stale,
		ReferenceIdentity reference, StringMeasurements string, Map<Integer, CellMeasurements> cells,
		AppliedThresholds thresholds, ActiveCalibrationContext activeCalibration, ModeEvidence operatingMode,
		BmsCurrentDirectionMapping currentDirectionMapping,
		Set<Alarm> activeAlarms, int alarmMask, List<AlarmDetail> alarmDetails, String qualityWarning,
		BmsCalculationSnapshot calculationSnapshot, UUID publicationGeneration, long publicationRevision,
		Map<Alarm, AlarmEvaluation> alarmEvaluations, EstimatorProvenance socEstimator,
		EstimatorProvenance sohEstimator) {

	public BmsMonitoringSnapshot {
		cells = Map.copyOf(cells);
		activeAlarms = Set.copyOf(activeAlarms);
		alarmDetails = List.copyOf(alarmDetails);
		alarmEvaluations = Map.copyOf(alarmEvaluations);
	}

	/** Compatibility constructor for callers using the pre-estimator contract. */
	public BmsMonitoringSnapshot(String stringKey, UUID snapshotGeneration, long snapshotSequence,
			Instant capturedAt, Long currentMeasuredAt, CurrentQuality currentQuality, boolean stale,
			ReferenceIdentity reference, StringMeasurements string, Map<Integer, CellMeasurements> cells,
			AppliedThresholds thresholds, ActiveCalibrationContext activeCalibration, ModeEvidence operatingMode,
			BmsCurrentDirectionMapping currentDirectionMapping, Set<Alarm> activeAlarms, int alarmMask,
			List<AlarmDetail> alarmDetails, String qualityWarning, BmsCalculationSnapshot calculationSnapshot,
			UUID publicationGeneration, long publicationRevision, Map<Alarm, AlarmEvaluation> alarmEvaluations) {
		this(stringKey, snapshotGeneration, snapshotSequence, capturedAt, currentMeasuredAt, currentQuality, stale,
				reference, string, cells, thresholds, activeCalibration, operatingMode, currentDirectionMapping,
				activeAlarms, alarmMask, alarmDetails, qualityWarning, calculationSnapshot, publicationGeneration,
				publicationRevision, alarmEvaluations, calculationSnapshot == null ? null : calculationSnapshot.socEstimator(),
				calculationSnapshot == null ? null : calculationSnapshot.sohEstimator());
	}

	/** Compatibility constructor for the original monitoring contract. */
	public BmsMonitoringSnapshot(String stringKey, UUID snapshotGeneration, long snapshotSequence,
			Instant capturedAt, Long currentMeasuredAt, CurrentQuality currentQuality, boolean stale,
			ReferenceIdentity reference, StringMeasurements string, Map<Integer, CellMeasurements> cells,
			AppliedThresholds thresholds, ActiveCalibrationContext activeCalibration, ModeEvidence operatingMode,
			BmsCurrentDirectionMapping currentDirectionMapping, Set<Alarm> activeAlarms, int alarmMask,
			List<AlarmDetail> alarmDetails, String qualityWarning) {
		this(stringKey, snapshotGeneration, snapshotSequence, capturedAt, currentMeasuredAt, currentQuality, stale,
				reference, string, cells, thresholds, activeCalibration, operatingMode, currentDirectionMapping,
				activeAlarms, alarmMask, alarmDetails, qualityWarning, null, snapshotGeneration, snapshotSequence,
				availableEvaluations(), null, null);
	}

	public UUID sourceGeneration() {
		return this.snapshotGeneration;
	}

	public long sourceSequence() {
		return this.snapshotSequence;
	}

	public boolean referenceAvailable() {
		return this.reference != null;
	}

	public BmsMonitoringSnapshot withPublication(UUID generation, long revision) {
		return new BmsMonitoringSnapshot(this.stringKey, this.snapshotGeneration, this.snapshotSequence,
				this.capturedAt, this.currentMeasuredAt, this.currentQuality, this.stale, this.reference, this.string,
				this.cells, this.thresholds, this.activeCalibration, this.operatingMode, this.currentDirectionMapping,
				this.activeAlarms, this.alarmMask, this.alarmDetails, this.qualityWarning, this.calculationSnapshot,
				generation, revision, this.alarmEvaluations, this.socEstimator, this.sohEstimator);
	}

	private static Map<Alarm, AlarmEvaluation> availableEvaluations() {
		var result = new EnumMap<Alarm, AlarmEvaluation>(Alarm.class);
		for (var alarm : Alarm.values()) result.put(alarm, AlarmEvaluation.AVAILABLE);
		return result;
	}

	/** UNAVAILABLE is neither an active condition nor evidence that it cleared. */
	public enum AlarmEvaluation {
		AVAILABLE, UNAVAILABLE
	}

	public record ReferenceIdentity(long calibrationEventId, long curveVersionId, long calibrationProcessId,
			int processAttemptNumber, long recoveryFloatSampleId, long vocSampleId, String contentHash) {
	}

	public enum OperatingMode {
		UNKNOWN, IDLE, CHARGING, FLOAT, DISCHARGING
	}

	public enum ModeSource {
		EXPLICIT_CHANNEL, ACTIVE_CALIBRATION, TELEMETRY_INFERENCE, NONE
	}

	public enum ModeQuality {
		VALID, CANDIDATE, STALE, UNAVAILABLE
	}

	/** Traceable evidence for the operating mode applied to this exact frame. */
	public record ModeEvidence(OperatingMode mode, ModeSource source, Instant resolvedAt,
			UUID snapshotGeneration, long snapshotSequence, ModeQuality quality, String reason) {
		public static ModeEvidence unknown(UUID generation, long sequence, Instant resolvedAt, String reason) {
			return new ModeEvidence(OperatingMode.UNKNOWN, ModeSource.NONE, resolvedAt, generation, sequence,
					ModeQuality.UNAVAILABLE, reason);
		}
	}

	public record StringMeasurements(Double batteryVoltageV, Double midpointVoltageV, Double batteryCurrentA,
			Double batteryPowerW, Double floatCurrentA, Double minCellVoltageV, Double maxCellVoltageV,
			Double averageCellVoltageV, Integer maxCellResistanceUohm, Double averageCellResistanceUohm,
			Double ambientTemperatureC, Double maxCellTemperatureC, Double averageCellTemperatureC,
			Double batterySocPct, Double remainingDischargeHours, Double batterySohPct,
			Double minimumCellSohPct) {
	}

	public record CellMeasurements(int cellIndex, Double voltageV, Double resistanceUohm,
			Double temperatureC, Double socPct, Double sohPct, String availabilityWarning,
			Double resistanceAlarmThresholdUohm, EstimatorProvenance socEstimator,
			EstimatorProvenance sohEstimator, Long measuredAtEpochMs, Long ageMs, CellMeasurementQuality quality) {
		public CellMeasurements {
			quality = quality == null ? CellMeasurementQuality.UNAVAILABLE : quality;
		}

		public CellMeasurements(int cellIndex, Double voltageV, Double resistanceUohm,
				Double temperatureC, Double socPct, Double sohPct, String availabilityWarning,
				Double resistanceAlarmThresholdUohm, EstimatorProvenance socEstimator,
				EstimatorProvenance sohEstimator) {
			this(cellIndex, voltageV, resistanceUohm, temperatureC, socPct, sohPct, availabilityWarning,
					resistanceAlarmThresholdUohm, socEstimator, sohEstimator, null, null,
					CellMeasurementQuality.FRESH);
		}
		public CellMeasurements(int cellIndex, Double voltageV, Double resistanceUohm,
				Double temperatureC, Double socPct, Double sohPct, String availabilityWarning,
				Double resistanceAlarmThresholdUohm) {
			this(cellIndex, voltageV, resistanceUohm, temperatureC, socPct, sohPct, availabilityWarning,
					resistanceAlarmThresholdUohm, null, null, null, null, CellMeasurementQuality.FRESH);
		}

		public CellMeasurements(int cellIndex, Double voltageV, Double resistanceUohm,
				Double temperatureC, Double socPct, Double sohPct, String availabilityWarning) {
			this(cellIndex, voltageV, resistanceUohm, temperatureC, socPct, sohPct, availabilityWarning, null,
					null, null, null, null, CellMeasurementQuality.FRESH);
		}
	}

	public enum CellMeasurementQuality {
		FRESH,
		RETAINED,
		STALE,
		UNAVAILABLE
	}

	public record AppliedThresholds(double lowCellDeviationFraction, double highCellDeviationFraction,
			double batteryVoltageLowV, double batteryVoltageHighV, double resistanceBaselineMultiplier,
			double cellTemperatureAboveAverageC, double cellTemperatureAboveAmbientC,
			double floatCurrentBaselineMultiplier, Double floatChargeOvercurrentA, Double chargeOvercurrentA, double lowSocPct,
			double highAmbientTemperatureC, double midpointImbalanceV) {
		public AppliedThresholds(double lowCellDeviationFraction, double highCellDeviationFraction,
				double batteryVoltageLowV, double batteryVoltageHighV, double resistanceBaselineMultiplier,
				double cellTemperatureAboveAverageC, double cellTemperatureAboveAmbientC,
				double floatCurrentBaselineMultiplier, double floatChargeOvercurrentA, double chargeOvercurrentA,
				double lowSocPct, double highAmbientTemperatureC, double midpointImbalanceV) {
			this(lowCellDeviationFraction, highCellDeviationFraction, batteryVoltageLowV, batteryVoltageHighV,
					resistanceBaselineMultiplier, cellTemperatureAboveAverageC, cellTemperatureAboveAmbientC,
					floatCurrentBaselineMultiplier, Double.valueOf(floatChargeOvercurrentA), Double.valueOf(chargeOvercurrentA),
					lowSocPct, highAmbientTemperatureC, midpointImbalanceV);
		}
	}

	/** Stable bit positions exported to Modbus and persisted alarm history. */
	public enum Alarm {
		LOW_CELL_VOLTAGE(0), HIGH_CELL_VOLTAGE(1), BATTERY_VOLTAGE_LOW(2), BATTERY_VOLTAGE_HIGH(3),
		CELL_RESISTANCE(4), CELL_TEMPERATURE(5), FLOAT_CHARGE_OVERCURRENT(6), CHARGE_OVERCURRENT(7),
		LOW_SOC(8), HIGH_AMBIENT_TEMPERATURE(9), MIDPOINT_VOLTAGE(10), SENSOR_MODULE_FAILURE(11);

		private final int bit;

		Alarm(int bit) {
			this.bit = bit;
		}

		public int bit() {
			return this.bit;
		}

		public int mask() {
			return 1 << this.bit;
		}
	}

	public record AlarmDetail(Alarm alarm, Integer cellIndex, Double observedValue,
			Double thresholdValue, String reason) {
	}
}
