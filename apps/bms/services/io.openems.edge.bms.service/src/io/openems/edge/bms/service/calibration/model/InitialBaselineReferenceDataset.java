package io.openems.edge.bms.service.calibration.model;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, approved Initial Baseline data used by monitoring calculations.
 *
 * <p>This is a historical reference. Runtime process checkpoints are deliberately
 * not part of this contract.</p>
 */
public record InitialBaselineReferenceDataset(long calibrationEventId, UUID stringId, String stringKey,
		int cellCount, double ratedCapacityAh, Instant completedAt, Instant approvedAt, UUID approvedBy,
		CurveVersion curve, PersistedSnapshot recoveryFloatSnapshot, PersistedSnapshot vocSnapshot,
		Map<String, Evidence> evidence, List<CellCutoff> cellCutoffs, List<CurvePoint> curvePoints,
		Map<Integer, CellCapacityReference> cellCapacityReferences) {

	public InitialBaselineReferenceDataset {
		evidence = Map.copyOf(evidence);
		cellCutoffs = List.copyOf(cellCutoffs);
		curvePoints = List.copyOf(curvePoints);
		cellCapacityReferences = cellCapacityReferences == null
				? capacityReferences(cellCutoffs) : Map.copyOf(cellCapacityReferences);
	}

	/** Compatibility constructor that derives the typed calculator lookup. */
	public InitialBaselineReferenceDataset(long calibrationEventId, UUID stringId, String stringKey,
			int cellCount, double ratedCapacityAh, Instant completedAt, Instant approvedAt, UUID approvedBy,
			CurveVersion curve, PersistedSnapshot recoveryFloatSnapshot, PersistedSnapshot vocSnapshot,
			Map<String, Evidence> evidence, List<CellCutoff> cellCutoffs, List<CurvePoint> curvePoints) {
		this(calibrationEventId, stringId, stringKey, cellCount, ratedCapacityAh, completedAt, approvedAt, approvedBy,
				curve, recoveryFloatSnapshot, vocSnapshot, evidence, cellCutoffs, curvePoints, null);
	}

	private static Map<Integer, CellCapacityReference> capacityReferences(List<CellCutoff> cutoffs) {
		var result = new LinkedHashMap<Integer, CellCapacityReference>();
		for (var cutoff : cutoffs) {
			if (cutoff == null) continue;
			var prior = result.put(cutoff.cellIndex(), new CellCapacityReference(cutoff.cellIndex(),
					cutoff.usableCapacityAh(), cutoff.sampleId(), cutoff.measurementTime(), cutoff.cutoffVoltageV()));
			if (prior != null) {
				throw new IllegalArgumentException("Duplicate capacity reference for cell " + cutoff.cellIndex());
			}
		}
		return Map.copyOf(result);
	}

	public record CurveVersion(long id, long calibrationProcessId, int processAttemptNumber,
			int schemaVersion, double capacityReferenceAh,
			double stringSafeUsableCapacityAh, double allCellsEndpointAh, int cellCount, int pointCount,
			String contentHash, Instant publishedAt) {
	}

	public record PersistedSnapshot(long sampleId, long processId, int processAttemptNumber,
			UUID snapshotGeneration, long snapshotSequence, Instant measurementTime, Double rawCurrentA,
			Double domainCurrentA, Double stringVoltageV, Double midpointVoltageV, Double averageVoltageV,
			Double ambientTemperatureC, Double averageCellTemperatureC, List<CellMeasurement> cells) {
		public PersistedSnapshot {
			cells = List.copyOf(cells);
		}
	}

	public record CellMeasurement(int cellIndex, Double voltageV, Double temperatureC,
			Integer resistanceUohm, Double socPct, Double sohPct, String quality,
			String availabilityWarning) {
	}

	public record Evidence(long id, String type, String status, double numericValue, String unit,
			Long calibrationSampleId, Instant recordedAt) {
	}

	public record CellCutoff(int cellIndex, long sampleId, double cutoffVoltageV,
			Instant measurementTime, double usableCapacityAh) {
	}

	/** Precise, immutable capacity view consumed by the realtime estimator. */
	public record CellCapacityReference(int cellIndex, double measuredCellCapacityAh, long cutoffSampleId,
			Instant cutoffMeasurementTime, double cutoffVoltageV) {
		public CellCapacityReference {
			if (cellIndex < 1 || !Double.isFinite(measuredCellCapacityAh) || measuredCellCapacityAh <= 0) {
				throw new IllegalArgumentException("Cell capacity reference must be finite and positive");
			}
		}
	}

	public record CurvePoint(int cellIndex, int pointOrder, long calibrationSampleId,
			double milestonePercent, double normalizedActualDodPercent, double dischargedAh,
			double cellVoltageV, Double cellTemperatureC, Integer cellResistanceUohm,
			Double cellSocPct, Double cellSohPct, Double stringVoltageV, Double averageVoltageV,
			String quality, boolean usable) {
	}
}
