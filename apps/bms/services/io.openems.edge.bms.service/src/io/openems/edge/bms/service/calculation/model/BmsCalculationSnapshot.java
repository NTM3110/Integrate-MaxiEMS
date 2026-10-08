package io.openems.edge.bms.service.calculation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable, completely published BMS calculation generation.
 *
 * The calculator builds this object privately and replaces the published
 * reference only after every configured cell has been processed. API readers
 * therefore never observe a half-cleared or partially rebuilt result map.
 */
public record BmsCalculationSnapshot(UUID generation, long sequence, long capturedAt, int configuredCellCount,
		Double ambientTemperature, Double stringCurrent, Long currentMeasuredAt, CurrentQuality currentQuality,
		String currentWarning, Double stringSoc, Double stringSoh,
		Double stringVoltage, Double midpointVoltage, Double averageVoltage, Double averageTemperature, Double averageResistance,
		Double maxVoltage, Integer maxVoltageCellIndex, Double minVoltage, Integer minVoltageCellIndex,
		Double maxTemperature, Integer maxTemperatureCellIndex, Double minTemperature, Integer minTemperatureCellIndex,
		Integer maxResistance, Integer maxResistanceCellIndex, Integer minResistance, Integer minResistanceCellIndex,
		Map<Integer, Cell> cells, String stringWarning, EstimatorProvenance socEstimator,
		EstimatorProvenance sohEstimator) {

	public BmsCalculationSnapshot {
		generation = Objects.requireNonNull(generation, "generation");
		currentQuality = Objects.requireNonNull(currentQuality, "currentQuality");
		cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
	}

	/** Compatibility constructor for producers without estimator provenance. */
	public BmsCalculationSnapshot(UUID generation, long sequence, long capturedAt, int configuredCellCount,
			Double ambientTemperature, Double stringCurrent, Long currentMeasuredAt, CurrentQuality currentQuality,
			String currentWarning, Double stringSoc, Double stringSoh, Double stringVoltage, Double midpointVoltage,
			Double averageVoltage, Double averageTemperature, Double averageResistance, Double maxVoltage,
			Integer maxVoltageCellIndex, Double minVoltage, Integer minVoltageCellIndex, Double maxTemperature,
			Integer maxTemperatureCellIndex, Double minTemperature, Integer minTemperatureCellIndex,
			Integer maxResistance, Integer maxResistanceCellIndex, Integer minResistance,
			Integer minResistanceCellIndex, Map<Integer, Cell> cells, String stringWarning) {
		this(generation, sequence, capturedAt, configuredCellCount, ambientTemperature, stringCurrent, currentMeasuredAt,
				currentQuality, currentWarning, stringSoc, stringSoh, stringVoltage, midpointVoltage, averageVoltage,
				averageTemperature, averageResistance, maxVoltage, maxVoltageCellIndex, minVoltage, minVoltageCellIndex,
				maxTemperature, maxTemperatureCellIndex, minTemperature, minTemperatureCellIndex, maxResistance,
				maxResistanceCellIndex, minResistance, minResistanceCellIndex, cells, stringWarning, null, null);
	}

	/** Compatibility constructor for snapshot producers that do not yet expose midpoint voltage. */
	public BmsCalculationSnapshot(UUID generation, long sequence, long capturedAt, int configuredCellCount,
			Double ambientTemperature, Double stringCurrent, Long currentMeasuredAt, CurrentQuality currentQuality,
			String currentWarning, Double stringSoc, Double stringSoh, Double stringVoltage, Double averageVoltage,
			Double averageTemperature, Double averageResistance, Double maxVoltage, Integer maxVoltageCellIndex,
			Double minVoltage, Integer minVoltageCellIndex, Double maxTemperature, Integer maxTemperatureCellIndex,
			Double minTemperature, Integer minTemperatureCellIndex, Integer maxResistance,
			Integer maxResistanceCellIndex, Integer minResistance, Integer minResistanceCellIndex,
			Map<Integer, Cell> cells, String stringWarning) {
		this(generation, sequence, capturedAt, configuredCellCount, ambientTemperature, stringCurrent, currentMeasuredAt,
				currentQuality, currentWarning, stringSoc, stringSoh, stringVoltage, null, averageVoltage,
				averageTemperature, averageResistance, maxVoltage, maxVoltageCellIndex, minVoltage, minVoltageCellIndex,
				maxTemperature, maxTemperatureCellIndex, minTemperature, minTemperatureCellIndex, maxResistance,
				maxResistanceCellIndex, minResistance, minResistanceCellIndex, cells, stringWarning, null, null);
	}

	/** Quality and freshness provenance of the TC string-current value. */
	public enum CurrentQuality {
		FRESH,
		RETAINED,
		MISSING,
		COMMUNICATION_FAILED,
		TIMESTAMP_MISSING
	}

	public int calculatedSocCellCount() {
		return (int) this.cells.values().stream().filter(cell -> cell.soc() != null).count();
	}

	public int calculatedSohCellCount() {
		return (int) this.cells.values().stream().filter(cell -> cell.soh() != null).count();
	}

	public enum EstimatorQuality {
		VALID, ESTIMATED, PROVISIONAL, AUTHORITATIVE, RETAINED, UNAVAILABLE
	}

	/** Provenance of the physical TA measurements represented by one cell. */
	public enum CellQuality {
		FRESH,
		RETAINED,
		UNAVAILABLE
	}

	public record EstimatorReferenceIdentity(Long calibrationEventId, Long curveVersionId,
			Long calibrationProcessId, Integer processAttemptNumber, String contentHash) {
	}

	public record EstimatorProvenance(String method, String state, EstimatorQuality quality, String note,
			EstimatorReferenceIdentity reference) {
	}

	public record Cell(int cellId, Double voltage, Double temperature, Integer internalResistance,
			Double soc, Double soh, Double ir, String availabilityWarning, EstimatorProvenance socEstimator,
			EstimatorProvenance sohEstimator, Long measuredAt, CellQuality quality) {
		public Cell {
			quality = quality == null ? CellQuality.UNAVAILABLE : quality;
		}

		public Cell(int cellId, Double voltage, Double temperature, Integer internalResistance,
				Double soc, Double soh, Double ir, String availabilityWarning, EstimatorProvenance socEstimator,
				EstimatorProvenance sohEstimator) {
			this(cellId, voltage, temperature, internalResistance, soc, soh, ir, availabilityWarning,
					socEstimator, sohEstimator, null, CellQuality.FRESH);
		}

		public Cell(int cellId, Double voltage, Double temperature, Integer internalResistance,
				Double soc, Double soh, Double ir, String availabilityWarning) {
			this(cellId, voltage, temperature, internalResistance, soc, soh, ir, availabilityWarning, null, null,
					null, CellQuality.FRESH);
		}
	}
}
