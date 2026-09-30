package io.openems.edge.bms.service.calibration.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

/** One snapshot adapted with the event's frozen current-polarity mapping. */
public record CalibrationMeasurement(UUID snapshotGeneration, long snapshotSequence, long capturedAt,
		int configuredCellCount, Double ambientTemperatureC, Double rawCurrentA, Double domainCurrentA,
		Long currentMeasuredAt, BmsCalculationSnapshot.CurrentQuality currentQuality, String currentWarning,
		DischargeCurrentDirection currentDirection, long mappingVersion, Double stringSocPct, Double stringSohPct,
		Double stringVoltageV, Double midpointVoltageV,
		Double averageVoltageV, Double averageTemperatureC, Double averageResistanceUohm,
		Map<Integer, Cell> cells, String stringWarning) {

	public CalibrationMeasurement {
		cells = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(cells, "cells")));
	}

	public static CalibrationMeasurement fromSnapshot(BmsCalculationSnapshot snapshot,
			CalibrationEventConfiguration configuration) {
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(configuration, "configuration");
		Double domainCurrent = snapshot.stringCurrent() == null ? null
				: configuration.currentDirection().toDomainCurrent(snapshot.stringCurrent());
		var cells = new LinkedHashMap<Integer, Cell>();
		for (var entry : snapshot.cells().entrySet()) {
			var cell = entry.getValue();
			cells.put(entry.getKey(), new Cell(cell.cellId(), cell.voltage(), cell.temperature(),
					cell.internalResistance(), cell.soc(), cell.soh(), cell.ir(), cell.availabilityWarning()));
		}
		return new CalibrationMeasurement(snapshot.generation(), snapshot.sequence(), snapshot.capturedAt(),
				snapshot.configuredCellCount(), snapshot.ambientTemperature(), snapshot.stringCurrent(), domainCurrent,
				snapshot.currentMeasuredAt(), snapshot.currentQuality(), snapshot.currentWarning(),
				configuration.currentDirection(), configuration.mappingVersion(), snapshot.stringSoc(), snapshot.stringSoh(),
				snapshot.stringVoltage(), snapshot.midpointVoltage(),
				snapshot.averageVoltage(), snapshot.averageTemperature(),
				snapshot.averageResistance(), cells, snapshot.stringWarning());
	}

	public record Cell(int cellId, Double voltageV, Double temperatureC, Integer internalResistanceUohm,
			Double socPct, Double sohPct, Double ir, String availabilityWarning) {
	}
}
