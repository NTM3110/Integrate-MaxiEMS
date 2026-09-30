package io.openems.edge.bms.service.strings.model;

import java.util.List;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

/** Value models used only by the controller-tools BMS string feature. */
public final class BmsStringModels {

	private BmsStringModels() {
	}

	public record Definition(int stringIndex, String stringName, int cellQty, String cellBrand, String cellModel,
			double ratedCapacity, double cutoffVoltage, double floatVoltage, double boostVoltage, double rNew,
			double highResistanceThreshold, double highTemperatureThreshold, double lowVoltageThreshold,
			double highVoltageThreshold, String bridgeId, int startTaUnitId, SocCheckpointPolicy socCheckpointPolicy) {
		public Definition(int stringIndex, String stringName, int cellQty, String cellBrand, String cellModel,
				double ratedCapacity, double cutoffVoltage, double floatVoltage, double boostVoltage, double rNew,
				double highResistanceThreshold, double highTemperatureThreshold, double lowVoltageThreshold,
				double highVoltageThreshold, String bridgeId, int startTaUnitId) {
			this(stringIndex, stringName, cellQty, cellBrand, cellModel, ratedCapacity, cutoffVoltage, floatVoltage,
					boostVoltage, rNew, highResistanceThreshold, highTemperatureThreshold, lowVoltageThreshold,
					highVoltageThreshold, bridgeId, startTaUnitId, SocCheckpointPolicy.DISABLED);
		}

		public Definition {
			socCheckpointPolicy = socCheckpointPolicy == null ? SocCheckpointPolicy.DISABLED : socCheckpointPolicy;
		}

		public String id() { return "str" + this.stringIndex; }
	}

	public record CellData(int cellId, Double voltage, Double temperature, Integer internalResistance, Double soc,
			Double soh, Double ir, String availabilityWarning, EstimatorProvenance socEstimator,
			EstimatorProvenance sohEstimator) {
		public CellData(int cellId, Double voltage, Double temperature, Integer internalResistance, Double soc,
				Double soh, Double ir, String availabilityWarning) {
			this(cellId, voltage, temperature, internalResistance, soc, soh, ir, availabilityWarning, null, null);
		}
	}

	public record StringData(Double ambientTemperature, Double stringCurrent, Double stringSoC, Double stringSoH,
			Double stringVoltage, Double midpointVoltage, Double avgVoltage, Double avgTemperature, Double avgResistance,
			Double maxVoltage, Integer maxVoltageCellIndex, Double minVoltage, Integer minVoltageCellIndex,
			Double maxTemperature, Integer maxTemperatureCellIndex, Double minTemperature, Integer minTemperatureCellIndex,
			Integer maxResistance, Integer maxResistanceCellIndex, Integer minResistance, Integer minResistanceCellIndex,
			EstimatorProvenance socEstimator, EstimatorProvenance sohEstimator) {
		public StringData(Double ambientTemperature, Double stringCurrent, Double stringSoC, Double stringSoH,
				Double stringVoltage, Double midpointVoltage, Double avgVoltage, Double avgTemperature, Double avgResistance,
				Double maxVoltage, Integer maxVoltageCellIndex, Double minVoltage, Integer minVoltageCellIndex,
				Double maxTemperature, Integer maxTemperatureCellIndex, Double minTemperature,
				Integer minTemperatureCellIndex, Integer maxResistance, Integer maxResistanceCellIndex,
				Integer minResistance, Integer minResistanceCellIndex) {
			this(ambientTemperature, stringCurrent, stringSoC, stringSoH, stringVoltage, midpointVoltage, avgVoltage,
					avgTemperature, avgResistance, maxVoltage, maxVoltageCellIndex, minVoltage, minVoltageCellIndex,
					maxTemperature, maxTemperatureCellIndex, minTemperature, minTemperatureCellIndex, maxResistance,
					maxResistanceCellIndex, minResistance, minResistanceCellIndex, null, null);
		}
	}

	/** Calculator state included in getData to make N/A values diagnosable. */
	public record CalculationStatus(boolean calculatorAvailable, int calculatedSocCellCount, int calculatedSohCellCount,
			Double cutoffVoltage, Double floatVoltage, Integer ratedResistanceUohm, String error,
			Long snapshotSequence, Long snapshotTimestamp, int consecutiveSnapshotFailures, boolean snapshotStale,
			Long snapshotAgeMs, List<Integer> unavailableTaCellIds, String stringWarning) {
	}

	public record Detail(Definition definition, boolean active, String statusMessage, StringData stringData,
			List<CellData> cellsData, CalculationStatus calculationStatus, BmsMonitoringSnapshot monitoring) {
		public Detail(Definition definition, boolean active, String statusMessage, StringData stringData,
				List<CellData> cellsData, CalculationStatus calculationStatus) {
			this(definition, active, statusMessage, stringData, cellsData, calculationStatus, null);
		}
	}
}
