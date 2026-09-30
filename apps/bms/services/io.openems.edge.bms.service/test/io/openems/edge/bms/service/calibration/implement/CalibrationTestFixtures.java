package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeMethod;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

public final class CalibrationTestFixtures {
	public static final UUID GENERATION = UUID.fromString("7b69b8a6-d87c-4acb-ad03-6c9f77c58985");
	public static final long BASE_TIME = 1_800_000_000_000L;

	private CalibrationTestFixtures() {
	}

	public static CalibrationEventConfiguration initial(DischargeCurrentDirection direction) {
		return configuration(CalibrationEventType.INITIAL_BASELINE, direction, DischargeMethod.C10,
				DischargeEndpointMode.FULL_CUTOFF, null);
	}

	public static CalibrationEventConfiguration periodic(DischargeCurrentDirection direction, Double targetDod) {
		return configuration(CalibrationEventType.PERIODIC_DISCHARGE, direction, DischargeMethod.PERIODIC,
				targetDod == null ? DischargeEndpointMode.FULL_CUTOFF : DischargeEndpointMode.PARTIAL_DOD, targetDod);
	}

	private static CalibrationEventConfiguration configuration(CalibrationEventType eventType,
			DischargeCurrentDirection direction, DischargeMethod method, DischargeEndpointMode endpointMode,
			Double targetDod) {
		var cutoffVoltagePerCell = eventType == CalibrationEventType.INITIAL_BASELINE
				? CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(method)
				: 1.85;
		return new CalibrationEventConfiguration(eventType, "str1", 3, 100.0, 100.0, 90.0,
				cutoffVoltagePerCell, 2.30, 2.45,
				direction, 7, -3.3, -5.0, 0.20, 10, 60, method, endpointMode, targetDod, 0.100,
				0.200, 0.300, "manufacturer-floor-v1");
	}

	public static CalibrationMeasurement measurement(CalibrationEventConfiguration configuration, long sequence,
			long measuredAt, double rawCurrentA, double temperatureC, double... voltages) {
		return measurement(configuration, GENERATION, sequence, measuredAt, rawCurrentA, temperatureC,
				BmsCalculationSnapshot.CurrentQuality.FRESH, null, voltages);
	}

	public static CalibrationMeasurement measurement(CalibrationEventConfiguration configuration, UUID generation,
			long sequence, long measuredAt, double rawCurrentA, double temperatureC,
			BmsCalculationSnapshot.CurrentQuality currentQuality, String unavailableCellWarning, double... voltages) {
		return measurement(configuration, generation, sequence, measuredAt, measuredAt, rawCurrentA, temperatureC,
				currentQuality, unavailableCellWarning, voltages);
	}

	public static CalibrationMeasurement measurement(CalibrationEventConfiguration configuration, UUID generation,
			long sequence, long capturedAt, Long currentMeasuredAt, double rawCurrentA, double temperatureC,
			BmsCalculationSnapshot.CurrentQuality currentQuality, String unavailableCellWarning, double... voltages) {
		var cells = new LinkedHashMap<Integer, BmsCalculationSnapshot.Cell>();
		for (int i = 0; i < voltages.length; i++) {
			cells.put(i + 1, new BmsCalculationSnapshot.Cell(i + 1, voltages[i], temperatureC, 1_000 + i, 90.0 - i,
					95.0 - i, 1_000.0 + i, i == voltages.length - 1 ? unavailableCellWarning : null));
		}
		var snapshot = new BmsCalculationSnapshot(generation, sequence, capturedAt, configuration.cellCount(), 25.0,
				rawCurrentA, currentMeasuredAt, currentQuality, currentQuality == BmsCalculationSnapshot.CurrentQuality.FRESH ? null
						: "current unavailable",
				90.0, 95.0, sum(voltages), midpoint(configuration.cellCount(), voltages), average(voltages), temperatureC, 1_001.0, max(voltages), 1,
				min(voltages), 1, temperatureC, 1, temperatureC, 1, 1_002, 3, 1_000, 1,
				Map.copyOf(cells), null);
		return CalibrationMeasurement.fromSnapshot(snapshot, configuration);
	}

	private static double sum(double[] values) {
		double sum = 0;
		for (double value : values) {
			sum += value;
		}
		return sum;
	}

	private static double average(double[] values) {
		return sum(values) / values.length;
	}

	private static double midpoint(int configuredCellCount, double[] values) {
		double sum = 0.0;
		for (var offset = 0; offset < Math.floorDiv(configuredCellCount, 2); offset++) sum += values[offset];
		return sum;
	}

	private static double min(double[] values) {
		double minimum = Double.POSITIVE_INFINITY;
		for (double value : values) {
			minimum = Math.min(minimum, value);
		}
		return minimum;
	}

	private static double max(double[] values) {
		double maximum = Double.NEGATIVE_INFINITY;
		for (double value : values) {
			maximum = Math.max(maximum, value);
		}
		return maximum;
	}

	public static CalibrationMeasurement withAmbient(CalibrationMeasurement value, Double ambientTemperatureC) {
		return new CalibrationMeasurement(value.snapshotGeneration(), value.snapshotSequence(), value.capturedAt(),
				value.configuredCellCount(), ambientTemperatureC, value.rawCurrentA(), value.domainCurrentA(),
				value.currentMeasuredAt(), value.currentQuality(), value.currentWarning(), value.currentDirection(),
				value.mappingVersion(), value.stringSocPct(), value.stringSohPct(), value.stringVoltageV(), value.midpointVoltageV(),
				value.averageVoltageV(), value.averageTemperatureC(), value.averageResistanceUohm(), value.cells(),
				value.stringWarning());
	}

	public static CalibrationMeasurement withStringVoltage(CalibrationMeasurement value, Double stringVoltageV) {
		return new CalibrationMeasurement(value.snapshotGeneration(), value.snapshotSequence(), value.capturedAt(),
				value.configuredCellCount(), value.ambientTemperatureC(), value.rawCurrentA(), value.domainCurrentA(),
				value.currentMeasuredAt(), value.currentQuality(), value.currentWarning(), value.currentDirection(),
				value.mappingVersion(), value.stringSocPct(), value.stringSohPct(), stringVoltageV, value.midpointVoltageV(),
				value.averageVoltageV(), value.averageTemperatureC(), value.averageResistanceUohm(), value.cells(),
				value.stringWarning());
	}
}
