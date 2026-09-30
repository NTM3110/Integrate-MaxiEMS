package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.monitors.ChargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.CooldownStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.FloatChargeStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.InspectionStageMonitor;
import io.openems.edge.bms.service.calibration.implement.monitors.VocBaselineMonitor;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;

import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;
import io.openems.edge.bms.service.calibration.model.ActiveCalibrationEstimatorContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.contracts.ActiveCalibrationContextProvider;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.SampleWrite;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.WorkerEvent;
import io.openems.edge.common.component.ComponentManager;

/** Evaluates the latest immutable calculator snapshot for each active event. */
@Component(name = "io.openems.edge.bms.platform.calibration.CalibrationEventWorker", service = { CalibrationEventWorker.class, ActiveCalibrationContextProvider.class }, immediate = true)
public class CalibrationEventWorker implements ActiveCalibrationContextProvider {

	private static final Gson GSON = new Gson();

	private final Logger log = LoggerFactory.getLogger(CalibrationEventWorker.class);

	@Reference
	private CalibrationEventStore store;
	/**
	 * Establishes activation order: the service first marks restart-sensitive
	 * physical stages as REVALIDATION_REQUIRED, then this worker may inspect active
	 * rows. Without this dependency the two immediate components can race during
	 * Edge startup and accept one measurement before revalidation is applied.
	 */
	@Reference
	private CalibrationEventService lifecycle;
	@Reference
	private ComponentManager componentManager;
	@Reference
	private ActiveCalibrationEstimatorContextCache estimatorContextCache;

	private ScheduledExecutorService executor;
	private volatile Map<String, ActiveCalibrationContext> activeContexts = Map.of();

	@Activate
	protected void activate() {
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "bms-calibration-event-worker");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.scheduleWithFixedDelay(this::tickSafely, 1, 1, TimeUnit.SECONDS);
	}

	@Deactivate
	protected void deactivate() {
		if (this.executor != null) this.executor.shutdownNow();
	}

	private void tickSafely() {
		try {
			var events = this.store.activeWorkerEvents();
			this.publishActiveContexts(events);
			for (var event : events) {
				try {
					this.process(event);
				} catch (Exception e) {
					this.log.error("Calibration worker failed for event {} and string {}", event.eventId(),
							event.stringKey(), e);
				}
			}
		} catch (Exception e) {
			this.log.error("Unable to query active calibration events", e);
		}
	}

	@Override
	public ActiveCalibrationContext activeContext(String stringKey) {
		return this.activeContexts.getOrDefault(stringKey, ActiveCalibrationContext.none());
	}

	private void publishActiveContexts(java.util.List<WorkerEvent> events) {
		var values = new LinkedHashMap<String, ActiveCalibrationContext>();
		var estimatorValues = new LinkedHashMap<String, ActiveCalibrationEstimatorContext>();
		var publishedAt = System.currentTimeMillis();
		for (var event : events) {
			if (event.processState() != CalibrationEventModels.ProcessState.ACTIVE) continue;
			values.put(event.stringKey(), new ActiveCalibrationContext(event.eventId(), event.stageId(),
					event.processId(), event.configuration().eventType().name(), event.stageType().name(),
					event.processType(), event.configuration().chargeCurrentLimitA()));
			var estimatorContext = estimatorContext(event, publishedAt);
			if (estimatorContext != null) estimatorValues.put(event.stringKey(), estimatorContext);
		}
		this.activeContexts = Map.copyOf(values);
		this.estimatorContextCache.publish(estimatorValues);
	}

	private static ActiveCalibrationEstimatorContext estimatorContext(WorkerEvent event, long publishedAt) {
		if (event.configuration().eventType() != CalibrationEventType.INITIAL_BASELINE
				|| event.stageType() != CalibrationStageType.DISCHARGE
				|| !"CONTROLLED_DISCHARGE".equals(event.processType())
				|| event.processId() == null || event.processAttemptNumber() == null) {
			return null;
		}
		var checkpoint = checkpoint(event, DischargeStageMonitor.Checkpoint.class);
		var cells = new LinkedHashMap<Integer, ActiveCalibrationEstimatorContext.CellProgress>();
		for (var cellIndex = 1; cellIndex <= event.configuration().cellCount(); cellIndex++) {
			var state = checkpoint == null ? null : checkpoint.perCellState().get(cellIndex);
			var reached = state != null && Boolean.TRUE.equals(state.cutoffReached());
			var capacity = reached ? state.cellUsableCapacityAh() : null;
			cells.put(cellIndex, new ActiveCalibrationEstimatorContext.CellProgress(cellIndex, reached, capacity));
		}
		return new ActiveCalibrationEstimatorContext(event.stringKey(), event.eventId(), event.stageId(),
				event.processId(), event.processAttemptNumber(), event.configuration().eventType().name(),
				event.stageType().name(), event.processType(), checkpoint == null ? null : checkpoint.snapshotGeneration(),
				checkpoint == null ? 0 : checkpoint.snapshotSequence(),
				checkpoint == null ? 0 : checkpoint.lastCurrentMeasuredAt(), publishedAt, cells);
	}

	private void process(WorkerEvent event) throws Exception {
		if (!hasAutomaticMonitor(event.stageType())) return;
		BmsStringCalculator calculator = this.componentManager.getComponent("ctrlBmsHuasuCalc-" + event.stringKey());
		var snapshot = calculator.latestSnapshot();
		if (snapshot == null) return;
		var measurement = CalibrationMeasurement.fromSnapshot(snapshot, event.configuration());
		long evaluatedAtMillis = System.currentTimeMillis();
		var evaluation = evaluate(event, measurement, evaluatedAtMillis);
		var blocker = evaluation.blockers().isEmpty() ? null : evaluation.blockers().get(0).name();
		var quality = evaluation.blockers().isEmpty() ? "VALID" : quality(evaluation.blockers().get(0));
		this.store.persistWorkerEvaluation(event,
				new SampleWrite(measurement, evaluation, quality, blocker, evaluatedAtMillis));
	}

	static CalibrationStageEvaluation<?> evaluate(WorkerEvent event, CalibrationMeasurement measurement,
			long nowMillis) {
		var configuration = configurationForEvaluation(event);
		/*
		 * A Float target identifies the second process inside a charge stage. It is
		 * not a monitor discriminator for every stage: persisted runtime properties
		 * can contain values from an earlier worker version. In particular, a
		 * PRE_DISCHARGE_FLOAT process must always be evaluated as cool-down, even if
		 * an old JSON envelope happens to contain a Float target.
		 */
		if ("FLOAT_SETTLEMENT_TO_VFLOAT".equals(event.processType())
				|| "RECOVERY_FLOAT_STABILIZATION".equals(event.processType())) {
			return new FloatChargeStageMonitor().evaluate(configuration, event.stageType(), measurement,
					checkpoint(event, FloatChargeStageMonitor.Checkpoint.class), nowMillis, event.demoTimeScaleFactor());
		}
		return switch (event.stageType()) {
		case INITIAL_CHARGE, RECHARGE -> new ChargeStageMonitor().evaluate(configuration, event.stageType(),
				measurement, checkpoint(event, ChargeStageMonitor.Checkpoint.class), nowMillis);
		case PRE_DISCHARGE_FLOAT -> new CooldownStageMonitor().evaluate(configuration, event.stageType(), measurement,
				checkpoint(event, CooldownStageMonitor.Checkpoint.class), nowMillis);
		case POST_RECHARGE_FLOAT -> new FloatChargeStageMonitor().evaluate(configuration, event.stageType(), measurement,
				checkpoint(event, FloatChargeStageMonitor.Checkpoint.class), nowMillis, event.demoTimeScaleFactor());
		case DISCHARGE -> new DischargeStageMonitor().evaluate(configuration, event.stageType(), measurement,
				checkpoint(event, DischargeStageMonitor.Checkpoint.class), nowMillis, event.demoTimeScaleFactor(),
				dischargeMaxWrongCurrentDirectionSamples(event));
		case BASELINE_CAPTURE -> new VocBaselineMonitor().evaluate(configuration, event.stageType(), measurement,
				checkpoint(event, VocBaselineMonitor.Checkpoint.class), nowMillis, vocCurrentDeadbandA(event));
		case PREREQUISITES, MONTHLY_INSPECTION, SIX_MONTH_INSPECTION -> new InspectionStageMonitor()
				.evaluate(configuration, event.stageType(), measurement,
						checkpoint(event, InspectionStageMonitor.Checkpoint.class), nowMillis,
						event.previousMonthlyEventId(), event.previousMonthlyFloatCurrentA(),
						event.previousMonthlyMeasuredAt());
		default -> throw new IllegalArgumentException("Stage has no automatic monitor: " + event.stageType());
		};
	}

	private static double vocCurrentDeadbandA(WorkerEvent event) {
		if (event.processProperties() == null || !event.processProperties().isJsonObject()) {
			return event.configuration().currentDeadbandA();
		}
		var value = event.processProperties().getAsJsonObject().get("vocCurrentDeadbandA");
		if (value == null || value.isJsonNull()) return event.configuration().currentDeadbandA();
		double result = value.getAsDouble();
		if (!Double.isFinite(result) || result < 0) {
			throw new IllegalArgumentException("Active Voc process has an invalid vocCurrentDeadbandA");
		}
		return result;
	}

	private static int dischargeMaxWrongCurrentDirectionSamples(WorkerEvent event) {
		if (event.processProperties() == null || !event.processProperties().isJsonObject()) {
			return DischargeStageMonitor.DEFAULT_MAX_WRONG_CURRENT_DIRECTION_SAMPLES;
		}
		var value = event.processProperties().getAsJsonObject()
				.get(DischargeStageMonitor.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY);
		if (value == null || value.isJsonNull()) {
			return DischargeStageMonitor.DEFAULT_MAX_WRONG_CURRENT_DIRECTION_SAMPLES;
		}
		try {
			int result = value.getAsInt();
			if (result <= 0) throw new IllegalArgumentException();
			return result;
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Active discharge process has an invalid "
					+ DischargeStageMonitor.MAX_WRONG_CURRENT_DIRECTION_SAMPLES_PROPERTY, e);
		}
	}

	/** Resolves the audited process-level Float policy without rewriting equipment configuration. */
	static CalibrationEventConfiguration configurationForEvaluation(WorkerEvent event) {
		if (event.processProperties() == null || !event.processProperties().isJsonObject()) return event.configuration();
		var properties = event.processProperties().getAsJsonObject();
		try {
			if (!properties.has("floatStableDurationMinutes") || !properties.has("floatVoltageToleranceV")
					|| !properties.has("floatCurrentMinA") || !properties.has("floatCurrentMaxA")) {
				return event.configuration();
			}
			var duration = properties.get("floatStableDurationMinutes").getAsInt();
			return event.configuration().withFloatStablePolicy(duration,
					properties.get("floatVoltageToleranceV").getAsDouble(),
					properties.get("floatCurrentMinA").getAsDouble(),
					properties.get("floatCurrentMaxA").getAsDouble());
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Active process has an invalid Float stable policy", e);
		}
	}

	private static <T> T checkpoint(WorkerEvent event, Class<T> type) {
		var runtimeCheckpoint = event.runtimeCheckpoint();
		if (runtimeCheckpoint != null && !runtimeCheckpoint.isJsonNull()
				&& !(runtimeCheckpoint.isJsonObject() && runtimeCheckpoint.getAsJsonObject().isEmpty())) {
			return GSON.fromJson(runtimeCheckpoint, type);
		}
		// Compatibility for processes started before calibration_process_runtime was
		// introduced. The first accepted evaluation copies this value to the runtime
		// row; new discharge evaluations no longer rewrite process.properties.
		var properties = event.processProperties();
		if (properties == null || properties.isJsonNull() || !properties.isJsonObject()) return null;
		var checkpoint = properties.getAsJsonObject().get("checkpoint");
		if (checkpoint == null || checkpoint.isJsonNull()
				|| checkpoint.isJsonObject() && checkpoint.getAsJsonObject().isEmpty()) return null;
		return GSON.fromJson(checkpoint, type);
	}

	private static boolean hasAutomaticMonitor(CalibrationStageType stageType) {
		return switch (stageType) {
		case WAITING_FOR_LOAD, LOAD_DISCONNECT, COMPLETION -> false;
		default -> true;
		};
	}

	private static String quality(CalibrationBlocker blocker) {
		return switch (blocker) {
		case SNAPSHOT_STALE -> "STALE";
		case CONFIGURATION_MISMATCH -> "CONFIGURATION_MISMATCH";
		case CURRENT_MISSING, CURRENT_STALE -> "CURRENT_UNAVAILABLE";
		case CELL_DATA_INCOMPLETE -> "CELL_DATA_INCOMPLETE";
		default -> "INVALID";
		};
	}
}
