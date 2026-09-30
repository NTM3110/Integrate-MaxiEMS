package io.openems.edge.bms.service.calibration.model;

import java.util.Objects;

import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

/** Immutable process-policy snapshot used for every decision in one event. */
public record CalibrationEventConfiguration(CalibrationEventType eventType, String stringKey, int cellCount,
		double ratedCapacityAh, double capacityReferenceAh, Double referenceBaselineAh, double cutoffVoltagePerCell,
		double floatVoltagePerCell, double boostVoltagePerCell, DischargeCurrentDirection currentDirection,
		long mappingVersion, double floatTemperatureCoefficientMvPerCellC,
		double boostTemperatureCoefficientMvPerCellC, double currentDeadbandA,
		int sampleIntervalSeconds, int snapshotFreshnessSeconds, DischargeMethod dischargeMethod,
		DischargeEndpointMode dischargeEndpointMode, Double targetDodPercent, double initialFloatCurrentLimitA,
		double periodicFloatCurrentLimitA, double floatOvercurrentReferenceA,
		String dischargeVoltageFloorPolicy, int floatStableDurationMinutes,
		double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA,
		double chargeCurrentLimitA, double safetyFloorVoltagePerCell) {

	public static final long CURRENT_MAPPING_VERSION = 1L;
	public static final String MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY = "maxWrongCurrentDirectionSamples";
	public static final double DEFAULT_FLOAT_TEMPERATURE_COEFFICIENT_MV_PER_CELL_C = -3.3;
	public static final double DEFAULT_BOOST_TEMPERATURE_COEFFICIENT_MV_PER_CELL_C = -5.0;
	public static final double REFERENCE_TEMPERATURE_C = 25.0;
	public static final double CURRENT_DEADBAND_A = 0.20;
	/** Persist automatic calibration evidence at most once per ten seconds. */
	public static final int SAMPLE_INTERVAL_SECONDS = 10;
	public static final int SNAPSHOT_FRESHNESS_SECONDS = 60;
	public static final double INITIAL_FLOAT_CURRENT_LIMIT_A = 0.100;
	public static final double PERIODIC_FLOAT_CURRENT_LIMIT_A = 0.200;
	public static final double FLOAT_OVERCURRENT_REFERENCE_A = 0.300;
	public static final String DISCHARGE_VOLTAGE_FLOOR_POLICY = "MANUFACTURER_APPROVED_FLOOR_V1";
	public static final int DEFAULT_FLOAT_STABLE_DURATION_MINUTES = 60;
	public static final double DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V = 1.0;
	/** Default Float-stability current window for newly created events. */
	public static final double DEFAULT_FLOAT_CURRENT_MIN_A = 0.0;
	public static final double DEFAULT_FLOAT_CURRENT_MAX_A = 0.1;
	/** Float-stability window used when reading a pre-window persisted configuration. */
	public static final double LEGACY_FLOAT_CURRENT_MIN_A = 0.1;
	public static final double LEGACY_FLOAT_CURRENT_MAX_A = 0.2;
	/** Default charger safety limit: 20% of the string rated capacity. */
	public static final double DEFAULT_CHARGE_CURRENT_LIMIT_CAPACITY_FRACTION = 0.20;
	/** End voltage for a 10-hour Initial Baseline discharge. */
	public static final double INITIAL_BASELINE_C10_CUTOFF_VOLTAGE_PER_CELL = 1.80;
	/** End voltage for a 5-hour Initial Baseline discharge. */
	public static final double INITIAL_BASELINE_C5_CUTOFF_VOLTAGE_PER_CELL = 1.75;
	/** Default safety margin below the configured discharge cutoff. */
	public static final double DEFAULT_SAFETY_FLOOR_MARGIN_V = 0.10;

	public CalibrationEventConfiguration {
		Objects.requireNonNull(eventType, "eventType");
		requireText(stringKey, "stringKey");
		if (cellCount <= 0) {
			throw new IllegalArgumentException("cellCount must be positive");
		}
		requirePositive(ratedCapacityAh, "ratedCapacityAh");
		requirePositive(capacityReferenceAh, "capacityReferenceAh");
		if (referenceBaselineAh != null) {
			requirePositive(referenceBaselineAh, "referenceBaselineAh");
		}
		requirePositive(cutoffVoltagePerCell, "cutoffVoltagePerCell");
		requirePositive(floatVoltagePerCell, "floatVoltagePerCell");
		requirePositive(boostVoltagePerCell, "boostVoltagePerCell");
		Objects.requireNonNull(currentDirection, "currentDirection");
		if (mappingVersion < 0) {
			throw new IllegalArgumentException("mappingVersion must not be negative");
		}
		requireFinite(floatTemperatureCoefficientMvPerCellC, "floatTemperatureCoefficientMvPerCellC");
		requireFinite(boostTemperatureCoefficientMvPerCellC, "boostTemperatureCoefficientMvPerCellC");
		requireNonNegative(currentDeadbandA, "currentDeadbandA");
		if (sampleIntervalSeconds <= 0 || sampleIntervalSeconds > 300) {
			throw new IllegalArgumentException("sampleIntervalSeconds must be between 1 and 300");
		}
		if (snapshotFreshnessSeconds <= 0) {
			throw new IllegalArgumentException("snapshotFreshnessSeconds must be positive");
		}
		requirePositive(initialFloatCurrentLimitA, "initialFloatCurrentLimitA");
		requirePositive(periodicFloatCurrentLimitA, "periodicFloatCurrentLimitA");
		requirePositive(floatOvercurrentReferenceA, "floatOvercurrentReferenceA");
		if (floatOvercurrentReferenceA <= periodicFloatCurrentLimitA) {
			throw new IllegalArgumentException("floatOvercurrentReferenceA must exceed the periodic limit");
		}
		requireText(dischargeVoltageFloorPolicy, "dischargeVoltageFloorPolicy");
		if (floatStableDurationMinutes <= 0) {
			throw new IllegalArgumentException("floatStableDurationMinutes must be positive");
		}
		requirePositive(floatVoltageToleranceV, "floatVoltageToleranceV");
		requireNonNegative(floatCurrentMinA, "floatCurrentMinA");
		requireNonNegative(floatCurrentMaxA, "floatCurrentMaxA");
		if (floatCurrentMinA > floatCurrentMaxA) {
			throw new IllegalArgumentException("floatCurrentMinA must not exceed floatCurrentMaxA");
		}
		requirePositive(chargeCurrentLimitA, "chargeCurrentLimitA");
		requirePositive(safetyFloorVoltagePerCell, "safetyFloorVoltagePerCell");
		if (safetyFloorVoltagePerCell >= cutoffVoltagePerCell) {
			throw new IllegalArgumentException("safetyFloorVoltagePerCell must be strictly below cutoffVoltagePerCell");
		}

		switch (eventType) {
		case INITIAL_BASELINE -> {
			if (dischargeMethod != DischargeMethod.C10 && dischargeMethod != DischargeMethod.C5) {
				throw new IllegalArgumentException("Initial Baseline dischargeMethod must be C10 or C5");
			}
			if (dischargeEndpointMode != DischargeEndpointMode.FULL_CUTOFF || targetDodPercent != null) {
				throw new IllegalArgumentException("Initial Baseline must use FULL_CUTOFF without target DOD");
			}
		}
		case PERIODIC_DISCHARGE -> {
			Objects.requireNonNull(dischargeMethod, "dischargeMethod");
			Objects.requireNonNull(dischargeEndpointMode, "dischargeEndpointMode");
			if (dischargeMethod != DischargeMethod.PERIODIC) {
				throw new IllegalArgumentException("Periodic Discharge dischargeMethod must be PERIODIC");
			}
			if (dischargeEndpointMode == DischargeEndpointMode.PARTIAL_DOD) {
				if (targetDodPercent == null || !Double.isFinite(targetDodPercent) || targetDodPercent < 35.0
						|| targetDodPercent > 65.0) {
					throw new IllegalArgumentException("Partial target DOD must be between 35 and 65 percent");
				}
			} else if (targetDodPercent != null) {
				throw new IllegalArgumentException("FULL_CUTOFF must not define target DOD");
			}
		}
		case MONTHLY_INSPECTION, SIX_MONTH_INSPECTION -> {
			if (dischargeMethod != null || dischargeEndpointMode != null || targetDodPercent != null) {
				throw new IllegalArgumentException("Inspection events must not define a discharge plan");
			}
		}
		}
	}

	/** Compatibility constructor for callers predating the discharge safety floor. */
	public CalibrationEventConfiguration(CalibrationEventType eventType, String stringKey, int cellCount,
			double ratedCapacityAh, double capacityReferenceAh, Double referenceBaselineAh, double cutoffVoltagePerCell,
			double floatVoltagePerCell, double boostVoltagePerCell, DischargeCurrentDirection currentDirection,
			long mappingVersion, double floatTemperatureCoefficientMvPerCellC,
			double boostTemperatureCoefficientMvPerCellC, double currentDeadbandA,
			int sampleIntervalSeconds, int snapshotFreshnessSeconds, DischargeMethod dischargeMethod,
			DischargeEndpointMode dischargeEndpointMode, Double targetDodPercent, double initialFloatCurrentLimitA,
			double periodicFloatCurrentLimitA, double floatOvercurrentReferenceA,
			String dischargeVoltageFloorPolicy, int floatStableDurationMinutes,
			double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA,
			double chargeCurrentLimitA) {
		this(eventType, stringKey, cellCount, ratedCapacityAh, capacityReferenceAh, referenceBaselineAh,
				cutoffVoltagePerCell, floatVoltagePerCell, boostVoltagePerCell, currentDirection, mappingVersion,
				floatTemperatureCoefficientMvPerCellC, boostTemperatureCoefficientMvPerCellC, currentDeadbandA,
				sampleIntervalSeconds, snapshotFreshnessSeconds, dischargeMethod, dischargeEndpointMode, targetDodPercent,
				initialFloatCurrentLimitA, periodicFloatCurrentLimitA, floatOvercurrentReferenceA,
				dischargeVoltageFloorPolicy, floatStableDurationMinutes, floatVoltageToleranceV, floatCurrentMinA,
				floatCurrentMaxA, chargeCurrentLimitA, defaultSafetyFloorVoltagePerCell(cutoffVoltagePerCell));
	}

	/** Compatibility constructor for callers predating the charger safety limit. */
	public CalibrationEventConfiguration(CalibrationEventType eventType, String stringKey, int cellCount,
			double ratedCapacityAh, double capacityReferenceAh, Double referenceBaselineAh, double cutoffVoltagePerCell,
			double floatVoltagePerCell, double boostVoltagePerCell, DischargeCurrentDirection currentDirection,
			long mappingVersion, double floatTemperatureCoefficientMvPerCellC,
			double boostTemperatureCoefficientMvPerCellC, double currentDeadbandA,
			int sampleIntervalSeconds, int snapshotFreshnessSeconds, DischargeMethod dischargeMethod,
			DischargeEndpointMode dischargeEndpointMode, Double targetDodPercent, double initialFloatCurrentLimitA,
			double periodicFloatCurrentLimitA, double floatOvercurrentReferenceA,
			String dischargeVoltageFloorPolicy, int floatStableDurationMinutes,
			double floatVoltageToleranceV, double floatCurrentMinA, double floatCurrentMaxA) {
		this(eventType, stringKey, cellCount, ratedCapacityAh, capacityReferenceAh, referenceBaselineAh,
				cutoffVoltagePerCell, floatVoltagePerCell, boostVoltagePerCell, currentDirection, mappingVersion,
				floatTemperatureCoefficientMvPerCellC, boostTemperatureCoefficientMvPerCellC, currentDeadbandA,
				sampleIntervalSeconds, snapshotFreshnessSeconds, dischargeMethod, dischargeEndpointMode, targetDodPercent,
				initialFloatCurrentLimitA, periodicFloatCurrentLimitA, floatOvercurrentReferenceA,
				dischargeVoltageFloorPolicy, floatStableDurationMinutes, floatVoltageToleranceV, floatCurrentMinA,
				floatCurrentMaxA, defaultChargeCurrentLimitA(ratedCapacityAh));
	}

	/** Compatibility constructor for persisted configuration call sites predating Float stability policy. */
	public CalibrationEventConfiguration(CalibrationEventType eventType, String stringKey, int cellCount,
			double ratedCapacityAh, double capacityReferenceAh, Double referenceBaselineAh, double cutoffVoltagePerCell,
			double floatVoltagePerCell, double boostVoltagePerCell, DischargeCurrentDirection currentDirection,
			long mappingVersion, double floatTemperatureCoefficientMvPerCellC,
			double boostTemperatureCoefficientMvPerCellC, double currentDeadbandA,
			int sampleIntervalSeconds, int snapshotFreshnessSeconds, DischargeMethod dischargeMethod,
			DischargeEndpointMode dischargeEndpointMode, Double targetDodPercent, double initialFloatCurrentLimitA,
			double periodicFloatCurrentLimitA, double floatOvercurrentReferenceA, String dischargeVoltageFloorPolicy) {
		this(eventType, stringKey, cellCount, ratedCapacityAh, capacityReferenceAh, referenceBaselineAh,
				cutoffVoltagePerCell, floatVoltagePerCell, boostVoltagePerCell, currentDirection, mappingVersion,
				floatTemperatureCoefficientMvPerCellC, boostTemperatureCoefficientMvPerCellC, currentDeadbandA,
				sampleIntervalSeconds, snapshotFreshnessSeconds, dischargeMethod, dischargeEndpointMode, targetDodPercent,
				initialFloatCurrentLimitA, periodicFloatCurrentLimitA, floatOvercurrentReferenceA,
				dischargeVoltageFloorPolicy, DEFAULT_FLOAT_STABLE_DURATION_MINUTES,
				DEFAULT_FLOAT_VOLTAGE_TOLERANCE_V, DEFAULT_FLOAT_CURRENT_MIN_A, DEFAULT_FLOAT_CURRENT_MAX_A,
				defaultChargeCurrentLimitA(ratedCapacityAh));
	}

	public static double defaultChargeCurrentLimitA(double ratedCapacityAh) {
		requirePositive(ratedCapacityAh, "ratedCapacityAh");
		return ratedCapacityAh * DEFAULT_CHARGE_CURRENT_LIMIT_CAPACITY_FRACTION;
	}

	/** Returns the discharge-method-specific cutoff for a new Initial Baseline. */
	public static double initialBaselineCutoffVoltagePerCell(DischargeMethod dischargeMethod) {
		return switch (Objects.requireNonNull(dischargeMethod, "dischargeMethod")) {
		case C10 -> INITIAL_BASELINE_C10_CUTOFF_VOLTAGE_PER_CELL;
		case C5 -> INITIAL_BASELINE_C5_CUTOFF_VOLTAGE_PER_CELL;
		case PERIODIC -> throw new IllegalArgumentException("Initial Baseline dischargeMethod must be C10 or C5");
		};
	}

	public static double defaultSafetyFloorVoltagePerCell(double cutoffVoltagePerCell) {
		requirePositive(cutoffVoltagePerCell, "cutoffVoltagePerCell");
		var value = cutoffVoltagePerCell - DEFAULT_SAFETY_FLOOR_MARGIN_V;
		if (value <= 0) {
			throw new IllegalArgumentException("cutoffVoltagePerCell must exceed the default safety margin");
		}
		return value;
	}

	public double floatCurrentLimitA() {
		return this.eventType == CalibrationEventType.INITIAL_BASELINE ? this.initialFloatCurrentLimitA
				: this.periodicFloatCurrentLimitA;
	}

	/** Returns an event-equivalent configuration with an audited Float-window override. */
	public CalibrationEventConfiguration withFloatStablePolicy(int durationMinutes, double voltageToleranceV,
			double currentMinA, double currentMaxA) {
		return new CalibrationEventConfiguration(this.eventType, this.stringKey, this.cellCount, this.ratedCapacityAh,
				this.capacityReferenceAh, this.referenceBaselineAh, this.cutoffVoltagePerCell, this.floatVoltagePerCell,
				this.boostVoltagePerCell, this.currentDirection, this.mappingVersion,
				this.floatTemperatureCoefficientMvPerCellC, this.boostTemperatureCoefficientMvPerCellC,
				this.currentDeadbandA, this.sampleIntervalSeconds, this.snapshotFreshnessSeconds, this.dischargeMethod,
				this.dischargeEndpointMode, this.targetDodPercent, this.initialFloatCurrentLimitA,
				this.periodicFloatCurrentLimitA, this.floatOvercurrentReferenceA, this.dischargeVoltageFloorPolicy,
				durationMinutes, voltageToleranceV, currentMinA, currentMaxA, this.chargeCurrentLimitA,
				this.safetyFloorVoltagePerCell);
	}

	/** Returns an event-equivalent configuration with a pre-discharge safety floor. */
	public CalibrationEventConfiguration withSafetyFloorVoltagePerCell(double value) {
		return new CalibrationEventConfiguration(this.eventType, this.stringKey, this.cellCount, this.ratedCapacityAh,
				this.capacityReferenceAh, this.referenceBaselineAh, this.cutoffVoltagePerCell, this.floatVoltagePerCell,
				this.boostVoltagePerCell, this.currentDirection, this.mappingVersion,
				this.floatTemperatureCoefficientMvPerCellC, this.boostTemperatureCoefficientMvPerCellC,
				this.currentDeadbandA, this.sampleIntervalSeconds, this.snapshotFreshnessSeconds, this.dischargeMethod,
				this.dischargeEndpointMode, this.targetDodPercent, this.initialFloatCurrentLimitA,
				this.periodicFloatCurrentLimitA, this.floatOvercurrentReferenceA, this.dischargeVoltageFloorPolicy,
				this.floatStableDurationMinutes, this.floatVoltageToleranceV, this.floatCurrentMinA,
				this.floatCurrentMaxA, this.chargeCurrentLimitA, value);
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
	}

	private static void requirePositive(double value, String name) {
		if (!Double.isFinite(value) || value <= 0) {
			throw new IllegalArgumentException(name + " must be finite and positive");
		}
	}

	private static void requireNonNegative(double value, String name) {
		if (!Double.isFinite(value) || value < 0) {
			throw new IllegalArgumentException(name + " must be finite and non-negative");
		}
	}

	private static void requireFinite(double value, String name) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite");
		}
	}

	public enum DischargeMethod {
		C10,
		C5,
		PERIODIC
	}

	public enum DischargeEndpointMode {
		PARTIAL_DOD,
		FULL_CUTOFF
	}

}
