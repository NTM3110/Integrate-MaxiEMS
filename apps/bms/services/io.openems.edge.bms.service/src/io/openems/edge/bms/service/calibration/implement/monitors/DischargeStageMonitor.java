package io.openems.edge.bms.service.calibration.implement.monitors;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.openems.edge.bms.service.calculation.model.CapacityTemperatureFactor;
import io.openems.edge.bms.service.calibration.implement.CalibrationDemoTimeScale;
import io.openems.edge.bms.service.calibration.implement.CalibrationMeasurementValidator;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Milestone;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.MilestoneType;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;

/** Integrates one fresh discharge snapshot at most once and freezes its first endpoint. */
public final class DischargeStageMonitor implements CalibrationStageMonitor<DischargeStageMonitor.Checkpoint> {

	/** Number of consecutive wrong-direction samples tolerated by default. */
	public static final int DEFAULT_MAX_WRONG_CURRENT_DIRECTION_SAMPLES = 3;
	public static final String MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY =
			CalibrationEventConfiguration.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY;

	@Override
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis) {
		return evaluate(configuration, stageType, measurement, checkpoint, nowMillis,
				CalibrationDemoTimeScale.REAL_TIME);
	}

	/**
	 * Evaluates a discharge sample using the event-owned demo factor only for Ah
	 * elapsed-time integration. Validation and timestamp/order safeguards always
	 * remain wall-clock based.
	 */
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis,
			int demoTimeScaleFactor) {
		return evaluate(configuration, stageType, measurement, checkpoint, nowMillis, demoTimeScaleFactor,
				DEFAULT_MAX_WRONG_CURRENT_DIRECTION_SAMPLES);
	}

	/**
	 * Evaluates one discharge measurement with an audited wrong-current-direction
	 * tolerance. The configured number is allowed; the following consecutive
	 * accepted fresh measurement blocks the process.
	 */
	public CalibrationStageEvaluation<Checkpoint> evaluate(CalibrationEventConfiguration configuration,
			CalibrationStageType stageType, CalibrationMeasurement measurement, Checkpoint checkpoint, long nowMillis,
			int demoTimeScaleFactor, int maxWrongCurrentDirectionSamples) {
		CalibrationDemoTimeScale.requireSupported(demoTimeScaleFactor);
		if (maxWrongCurrentDirectionSamples <= 0) {
			throw new IllegalArgumentException("maxWrongCurrentDirectionSamples must be positive");
		}
		if (stageType != CalibrationStageType.DISCHARGE) {
			throw new IllegalArgumentException("DischargeStageMonitor does not support " + stageType);
		}
		if (checkpoint != null && checkpoint.endpoint() != null
				&& checkpoint.effectiveSafetyState() != SafetyState.LATCHED) {
			if (checkpoint.endpoint().resultMethod() == CapacityResultMethod.FULL_CUTOFF
					&& checkpoint.endpoint().kcFactor() == null) {
				return new CalibrationStageEvaluation<>(checkpoint,
						List.of(CalibrationBlocker.KC_TEMPERATURE_OUT_OF_RANGE), List.of(), List.of(),
						Recommendation.BLOCKED);
			}
			return new CalibrationStageEvaluation<>(checkpoint, List.of(), List.of(), List.of(),
					Recommendation.READY_TO_COMPLETE);
		}
		var blockers = new ArrayList<>(CalibrationMeasurementValidator.validate(configuration, measurement, nowMillis,
				CalibrationMeasurementValidator.Requirements.discharge()));
		if (blockers.isEmpty()) addOrderingBlockers(measurement, checkpoint, blockers);
		if (!blockers.isEmpty()) {
			return new CalibrationStageEvaluation<>(checkpoint, blockers, List.of(),
					List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
		}

		var safetyState = checkpoint == null ? SafetyState.CLEAR : checkpoint.effectiveSafetyState();
		boolean wrongCurrentDirection = safetyState != SafetyState.LATCHED
				&& measurement.domainCurrentA() <= configuration.currentDeadbandA();
		int consecutiveWrongCurrentDirectionSamples = wrongCurrentDirection
				? (checkpoint == null ? 0 : checkpoint.consecutiveWrongCurrentDirectionSamples()) + 1
				: 0;
		if (wrongCurrentDirection) {
			var updated = directionCheckpoint(measurement, checkpoint, consecutiveWrongCurrentDirectionSamples);
			if (consecutiveWrongCurrentDirectionSamples > maxWrongCurrentDirectionSamples) {
				return new CalibrationStageEvaluation<>(updated,
						List.of(CalibrationBlocker.PHASE_CURRENT_INCONSISTENT), List.of(),
						List.of(PersistenceReason.QUALITY_CHANGE), Recommendation.BLOCKED);
			}
			return new CalibrationStageEvaluation<>(updated, List.of(), List.of(), List.of(), Recommendation.CONTINUE);
		}
		double rawUsedAh = checkpoint == null ? 0.0 : checkpoint.rawUsedAh();
		double deltaAh = 0.0;
		// A calculator activate/modified publishes a new opaque generation. The
		// first accepted sample in that generation is an anchor only: integrating
		// from the previous generation would count the restart/revalidation gap.
		if (safetyState != SafetyState.LATCHED && isContinuousMeasurement(configuration, measurement, checkpoint)) {
			long wallClockElapsedMillis = measurement.currentMeasuredAt() - checkpoint.lastCurrentMeasuredAt();
			long elapsedMillis = CalibrationDemoTimeScale.scaledElapsedMillis(wallClockElapsedMillis,
					demoTimeScaleFactor);
			double elapsedHours = elapsedMillis / 3_600_000.0;
			// Trapezoidal integration uses both fresh endpoint samples rather than
			// assuming the latest current held for the complete preceding interval.
			deltaAh = (Math.max(0.0, checkpoint.lastDomainCurrentA())
					+ Math.max(0.0, measurement.domainCurrentA())) / 2.0 * elapsedHours;
			rawUsedAh += deltaAh;
		}
		double dodPercent = rawUsedAh / configuration.capacityReferenceAh() * 100.0;
		double temperatureSum = (checkpoint == null ? 0.0 : checkpoint.temperatureSum())
				+ averageCellTemperature(measurement);
		int temperatureSampleCount = (checkpoint == null ? 0 : checkpoint.temperatureSampleCount()) + 1;
		int previousMilestone = checkpoint == null ? 0 : checkpoint.lastDodMilestonePercent();
		int currentMilestone = Math.min(100, ((int) Math.floor(dodPercent / 5.0)) * 5);
		var milestones = new ArrayList<Milestone>();
		var persistence = new ArrayList<PersistenceReason>();
		persistence.add(PersistenceReason.PERIODIC_SAMPLE);
		if (currentMilestone > previousMilestone) {
			for (int milestone = previousMilestone + 5; milestone <= currentMilestone; milestone += 5) {
				milestones.add(new Milestone(MilestoneType.DOD_PERCENT, milestone));
			}
			persistence.add(PersistenceReason.DOD_MILESTONE_5_PERCENT);
		}

		boolean sameGeneration = checkpoint != null
				&& measurement.snapshotGeneration().equals(checkpoint.snapshotGeneration());
		Map<Integer, PerCellState> previousCellState = checkpoint == null ? Map.of()
				: sameGeneration ? checkpoint.perCellState()
						: checkpoint.perCellState().entrySet().stream()
								.filter(entry -> Boolean.TRUE.equals(entry.getValue().cutoffReached()))
								.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		var cellUpdate = safetyState == SafetyState.LATCHED
				? new CellUpdate(previousCellState, List.of())
				: updatePerCellState(configuration, measurement, rawUsedAh, previousCellState);
		var safetyTriggers = checkpoint == null ? List.<SafetyTrigger>of() : checkpoint.safetyTriggers();
		var newSafetyTriggers = List.<SafetyTrigger>of();
		Double frozenUsedAh = checkpoint == null ? null : checkpoint.frozenUsedAh();
		if (safetyState == SafetyState.CLEAR) {
			newSafetyTriggers = measurement.cells().values().stream()
					.filter(cell -> cell.voltageV() <= configuration.safetyFloorVoltagePerCell())
					.map(cell -> new SafetyTrigger(cell.cellId(), cell.voltageV())).toList();
			if (!newSafetyTriggers.isEmpty()) {
				safetyState = SafetyState.LATCHED;
				safetyTriggers = newSafetyTriggers;
				frozenUsedAh = rawUsedAh;
				persistence.add(PersistenceReason.SAFETY_FLOOR);
			}
		}
		Endpoint endpoint = null;
		if (configuration.dischargeEndpointMode() == DischargeEndpointMode.FULL_CUTOFF) {
			if (!cellUpdate.newEndpoints().isEmpty()) {
				milestones.add(new Milestone(MilestoneType.CUTOFF, configuration.cutoffVoltagePerCell()));
				persistence.add(PersistenceReason.CUTOFF);
			}
			if (cellUpdate.states().size() == configuration.cellCount()
					&& cellUpdate.states().values().stream().allMatch(value -> Boolean.TRUE.equals(value.cutoffReached()))) {
				var last = cellUpdate.newEndpoints().isEmpty() ? null
						: cellUpdate.newEndpoints().get(cellUpdate.newEndpoints().size() - 1);
				endpoint = endpoint(configuration, EndpointType.CUTOFF, last == null ? null : last.cellIndex(),
						configuration.cutoffVoltagePerCell(), measurement, rawUsedAh, temperatureSum,
						temperatureSampleCount);
			}
		} else if (configuration.dischargeEndpointMode() == DischargeEndpointMode.PARTIAL_DOD
				&& dodPercent >= configuration.targetDodPercent()) {
			endpoint = endpoint(configuration, EndpointType.TARGET_DOD, null, null, measurement, rawUsedAh,
					temperatureSum, temperatureSampleCount);
			milestones.add(new Milestone(MilestoneType.TARGET_DOD, dodPercent));
			persistence.add(PersistenceReason.TARGET_DOD);
		}
		var updated = new Checkpoint(measurement.snapshotGeneration(), measurement.snapshotSequence(),
				measurement.capturedAt(), measurement.currentMeasuredAt(), measurement.domainCurrentA(), deltaAh, rawUsedAh, dodPercent,
				Math.max(previousMilestone, currentMilestone), temperatureSum, temperatureSampleCount, endpoint,
				cellUpdate.states(), cellUpdate.newEndpoints(), safetyState, frozenUsedAh, safetyTriggers,
				newSafetyTriggers, consecutiveWrongCurrentDirectionSamples);
		if (endpoint != null && endpoint.kcFactor() == null
				&& endpoint.resultMethod() == CapacityResultMethod.FULL_CUTOFF) {
			return new CalibrationStageEvaluation<>(updated,
					List.of(CalibrationBlocker.KC_TEMPERATURE_OUT_OF_RANGE), milestones, persistence,
					Recommendation.BLOCKED);
		}
		return new CalibrationStageEvaluation<>(updated, List.of(), milestones, persistence,
				safetyState == SafetyState.LATCHED ? Recommendation.CONTINUE
						: endpoint == null ? Recommendation.CONTINUE : Recommendation.READY_TO_COMPLETE);
	}

	private static void addOrderingBlockers(CalibrationMeasurement measurement, Checkpoint checkpoint,
			List<CalibrationBlocker> blockers) {
		if (checkpoint == null) {
			return;
		}
		if (measurement.snapshotGeneration().equals(checkpoint.snapshotGeneration())) {
			if (measurement.snapshotSequence() == checkpoint.snapshotSequence()) {
				blockers.add(CalibrationBlocker.MEASUREMENT_SEQUENCE_REPEATED);
			} else if (measurement.snapshotSequence() < checkpoint.snapshotSequence()) {
				blockers.add(CalibrationBlocker.MEASUREMENT_SEQUENCE_REGRESSED);
			}
		}
		if (measurement.capturedAt() <= checkpoint.lastCapturedAt()
				|| measurement.currentMeasuredAt() <= checkpoint.lastCurrentMeasuredAt()) {
			blockers.add(CalibrationBlocker.MEASUREMENT_TIME_REGRESSED);
		}
	}

	private static Checkpoint directionCheckpoint(CalibrationMeasurement measurement, Checkpoint checkpoint,
			int consecutiveWrongCurrentDirectionSamples) {
		return new Checkpoint(measurement.snapshotGeneration(), measurement.snapshotSequence(), measurement.capturedAt(),
				measurement.currentMeasuredAt(), measurement.domainCurrentA(), 0.0,
				checkpoint == null ? 0.0 : checkpoint.rawUsedAh(), checkpoint == null ? 0.0 : checkpoint.dodPercent(),
				checkpoint == null ? 0 : checkpoint.lastDodMilestonePercent(),
				checkpoint == null ? 0.0 : checkpoint.temperatureSum(),
				checkpoint == null ? 0 : checkpoint.temperatureSampleCount(), checkpoint == null ? null : checkpoint.endpoint(),
				checkpoint == null ? Map.of() : checkpoint.perCellState(), List.of(),
				checkpoint == null ? SafetyState.CLEAR : checkpoint.effectiveSafetyState(),
				checkpoint == null ? null : checkpoint.frozenUsedAh(),
				checkpoint == null ? List.of() : checkpoint.safetyTriggers(), List.of(),
				consecutiveWrongCurrentDirectionSamples);
	}

	/**
	 * A valid but discontinuous measurement is an anchor, not an integration
	 * interval. This prevents an Edge/worker outage from being counted as supplied
	 * discharge energy even when the calculator generation happened to survive.
	 */
	private static boolean isContinuousMeasurement(CalibrationEventConfiguration configuration,
			CalibrationMeasurement measurement, Checkpoint checkpoint) {
		if (checkpoint == null || !measurement.snapshotGeneration().equals(checkpoint.snapshotGeneration())) {
			return false;
		}
		// Wrong-direction samples advance the ordering anchor but are deliberately not
		// integrated. The first valid sample after such a streak must also remain an
		// anchor; otherwise trapezoidal integration would count part of the invalid
		// interval and inflate discharged Ah.
		if (checkpoint.consecutiveWrongCurrentDirectionSamples() > 0) {
			return false;
		}
		long elapsedMillis = measurement.currentMeasuredAt() - checkpoint.lastCurrentMeasuredAt();
		return elapsedMillis > 0 && elapsedMillis <= configuration.sampleIntervalSeconds() * 1_000L;
	}

	private static CellUpdate updatePerCellState(CalibrationEventConfiguration configuration,
			CalibrationMeasurement measurement, double rawUsedAh, Map<Integer, PerCellState> previous) {
		var states = new LinkedHashMap<Integer, PerCellState>();
		var endpoints = new ArrayList<CutoffEndpoint>();
		for (int cellIndex = 1; cellIndex <= configuration.cellCount(); cellIndex++) {
			var cell = measurement.cells().get(cellIndex);
			var prior = previous.get(cellIndex);
			if (prior != null && Boolean.TRUE.equals(prior.cutoffReached())) {
				states.put(cellIndex, prior);
				continue;
			}
			if (cell.voltageV() <= configuration.cutoffVoltagePerCell()) {
				var state = new PerCellState(cell.voltageV(), rawUsedAh, Boolean.TRUE,
						cell.voltageV(), rawUsedAh);
				states.put(cellIndex, state);
				endpoints.add(new CutoffEndpoint(cellIndex, cell.voltageV(), rawUsedAh));
			} else {
				states.put(cellIndex, new PerCellState(cell.voltageV(), rawUsedAh, null, null, null));
			}
		}
		return new CellUpdate(states, endpoints);
	}

	private static Endpoint endpoint(CalibrationEventConfiguration configuration, EndpointType endpointType,
			Integer cellId, Double cellVoltageV, CalibrationMeasurement measurement, double rawUsedAh,
			double temperatureSum, int temperatureSampleCount) {
		double representativeTemperatureC = temperatureSum / temperatureSampleCount;
		if (endpointType == EndpointType.TARGET_DOD) {
			return new Endpoint(endpointType, cellId, cellVoltageV, measurement.snapshotGeneration(),
					measurement.snapshotSequence(), measurement.currentMeasuredAt(), rawUsedAh, representativeTemperatureC,
					null, null, CapacityResultMethod.PARTIAL_DOD_COMPARISON, null);
		}
		Double factor = CapacityTemperatureFactor.factor(representativeTemperatureC);
		Double correctedAh = factor == null ? null : rawUsedAh * factor;
		Double capacitySoh = correctedAh == null || configuration.referenceBaselineAh() == null ? null
				: correctedAh / configuration.referenceBaselineAh() * 100.0;
		return new Endpoint(endpointType, cellId, cellVoltageV, measurement.snapshotGeneration(),
				measurement.snapshotSequence(), measurement.currentMeasuredAt(), rawUsedAh, representativeTemperatureC,
				factor, correctedAh, CapacityResultMethod.FULL_CUTOFF, capacitySoh);
	}

	private static double averageCellTemperature(CalibrationMeasurement measurement) {
		return measurement.cells().values().stream().mapToDouble(CalibrationMeasurement.Cell::temperatureC).average()
				.orElseThrow();
	}

	public record Checkpoint(UUID snapshotGeneration, long snapshotSequence, long lastCapturedAt,
			long lastCurrentMeasuredAt, double lastDomainCurrentA, double lastDeltaAh, double rawUsedAh, double dodPercent,
			int lastDodMilestonePercent, double temperatureSum, int temperatureSampleCount, Endpoint endpoint,
			Map<Integer, PerCellState> perCellState, List<CutoffEndpoint> newCutoffEndpoints,
			SafetyState safetyState, Double frozenUsedAh, List<SafetyTrigger> safetyTriggers,
			List<SafetyTrigger> newSafetyTriggers, int consecutiveWrongCurrentDirectionSamples) {
		public Checkpoint {
			if (consecutiveWrongCurrentDirectionSamples < 0) {
				throw new IllegalArgumentException("consecutiveWrongCurrentDirectionSamples must not be negative");
			}
			perCellState = perCellState == null ? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(perCellState));
			newCutoffEndpoints = newCutoffEndpoints == null ? List.of() : List.copyOf(newCutoffEndpoints);
			safetyTriggers = safetyTriggers == null ? List.of() : List.copyOf(safetyTriggers);
			newSafetyTriggers = newSafetyTriggers == null ? List.of() : List.copyOf(newSafetyTriggers);
		}

		public Checkpoint(UUID snapshotGeneration, long snapshotSequence, long lastCapturedAt,
				long lastCurrentMeasuredAt, double lastDomainCurrentA, double lastDeltaAh, double rawUsedAh,
				double dodPercent, int lastDodMilestonePercent, double temperatureSum, int temperatureSampleCount,
				Endpoint endpoint, Map<Integer, PerCellState> perCellState, List<CutoffEndpoint> newCutoffEndpoints) {
			this(snapshotGeneration, snapshotSequence, lastCapturedAt, lastCurrentMeasuredAt, lastDomainCurrentA,
					lastDeltaAh, rawUsedAh, dodPercent, lastDodMilestonePercent, temperatureSum,
					temperatureSampleCount, endpoint, perCellState, newCutoffEndpoints, SafetyState.CLEAR, null,
					List.of(), List.of(), 0);
		}

		public SafetyState effectiveSafetyState() {
			return safetyState == null ? SafetyState.CLEAR : safetyState;
		}
	}

	public record PerCellState(double lastVoltageV, double lastRawUsedAh, Boolean cutoffReached,
			Double cutoffVoltageV, Double cellUsableCapacityAh) {
	}

	public record CutoffEndpoint(int cellIndex, double cutoffVoltageV, double cellUsableCapacityAh) {
	}

	public record SafetyTrigger(int cellIndex, double voltageV) {
	}

	public enum SafetyState {
		CLEAR,
		LATCHED,
		STOP_CONFIRMED
	}

	private record CellUpdate(Map<Integer, PerCellState> states, List<CutoffEndpoint> newEndpoints) {
	}

	public record Endpoint(EndpointType type, Integer cellId, Double cellVoltageV, UUID snapshotGeneration,
			long snapshotSequence, long measurementTime, double rawUsedAh, double representativeTemperatureC,
			Double kcFactor, Double correctedAh, CapacityResultMethod resultMethod, Double capacitySohPercent) {
	}

	public enum EndpointType {
		CUTOFF,
		TARGET_DOD
	}

	public enum CapacityResultMethod {
		FULL_CUTOFF,
		PARTIAL_DOD_COMPARISON
	}
}
