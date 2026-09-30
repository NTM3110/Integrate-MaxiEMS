package io.openems.edge.bms.service.calculation.implement.checkpoint;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.service.calculation.contracts.SocEstimatorCheckpointStore;
import io.openems.edge.bms.service.calculation.implement.checkpoint.persistence.SocEstimatorCheckpointRepository;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

/** Coalesces checkpoint writes and performs all JDBC work off-cycle. */
@Component(name = "Bms.Calculation.SocCheckpoint", immediate = true,
		service = SocEstimatorCheckpointStore.class)
public class AsyncSocEstimatorCheckpointStore implements SocEstimatorCheckpointStore {

	private final Logger log = LoggerFactory.getLogger(AsyncSocEstimatorCheckpointStore.class);
	private final Map<String, SocEstimatorCheckpoint> latest = new ConcurrentHashMap<>();
	private final Map<String, SocEstimatorCheckpoint> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean drainScheduled = new AtomicBoolean();

	@Reference
	private BmsDatabase database;
	private volatile SocEstimatorCheckpointRepository repository;
	private ExecutorService executor;

	@Activate
	protected synchronized void activate() {
		this.repository = new SocEstimatorCheckpointRepository(this.database);
		this.loadSafely();
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			var thread = new Thread(runnable, "bms-soc-checkpoint-writer");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Deactivate
	protected synchronized void deactivate() {
		var activeExecutor = this.executor;
		this.executor = null;
		if (activeExecutor != null) {
			activeExecutor.shutdown();
			try {
				if (!activeExecutor.awaitTermination(10, TimeUnit.SECONDS)) activeExecutor.shutdownNow();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				activeExecutor.shutdownNow();
			}
		}
		if (!this.pending.isEmpty()) {
			this.log.warn("Discarding {} unpersisted SoC estimator checkpoint(s) during shutdown", this.pending.size());
			this.pending.clear();
		}
		this.drainScheduled.set(false);
	}

	@Override
	public Optional<SocEstimatorCheckpoint> latest(String stringKey) {
		return Optional.ofNullable(this.latest.get(stringKey));
	}

	@Override
	public void publish(SocEstimatorCheckpoint checkpoint) {
		if (!persistable(checkpoint)) return;
		this.pending.merge(checkpoint.stringKey(), checkpoint, AsyncSocEstimatorCheckpointStore::newest);
		this.scheduleDrain();
	}

	@Override
	public void clear(String stringKey) {
		if (stringKey == null || stringKey.isBlank()) return;
		this.latest.remove(stringKey);
		this.pending.remove(stringKey);
		var activeExecutor = this.executor;
		var activeRepository = this.repository;
		if (activeExecutor != null && activeRepository != null) {
			try {
				activeExecutor.execute(() -> {
					try {
						activeRepository.delete(stringKey);
					} catch (Exception error) {
						this.log.warn("Unable to delete SoC estimator checkpoint for [{}]", stringKey, error);
					}
				});
			} catch (RejectedExecutionException ignored) {
				// Component shutdown won the race; no executor remains that could recreate it.
			}
		}
	}

	private void loadSafely() {
		try {
			if (this.database.isAvailable()) this.latest.putAll(this.repository.loadAll());
		} catch (Exception error) {
			this.log.warn("Unable to load SoC estimator checkpoints", error);
		}
	}

	private void scheduleDrain() {
		var activeExecutor = this.executor;
		if (activeExecutor != null && this.drainScheduled.compareAndSet(false, true)) {
			try {
				activeExecutor.execute(this::drainSafely);
			} catch (RejectedExecutionException ignored) {
				this.drainScheduled.set(false);
			}
		}
	}

	private void drainSafely() {
		try {
			while (!this.pending.isEmpty()) {
				var batch = new HashMap<>(this.pending);
				for (var entry : batch.entrySet()) {
					if (!this.pending.remove(entry.getKey(), entry.getValue())) continue;
					try {
						if (this.repository.save(entry.getValue())) this.latest.put(entry.getKey(), entry.getValue());
					} catch (Exception error) {
						this.log.warn("Unable to persist SoC estimator checkpoint for [{}]", entry.getKey(), error);
					}
				}
			}
		} finally {
			this.drainScheduled.set(false);
			if (!this.pending.isEmpty()) this.scheduleDrain();
		}
	}

	static boolean persistable(SocEstimatorCheckpoint value) {
		if (value == null || value.schemaVersion() != SocEstimatorCheckpoint.CURRENT_SCHEMA_VERSION
				|| value.stringKey() == null || value.stringKey().isBlank() || value.cellCount() <= 0
				|| value.sourceGeneration() == null || value.sourceSequence() < 0 || value.measurementTimestamp() <= 0
				|| value.reference() == null || value.reference().calibrationEventId() == null
				|| value.reference().curveVersionId() == null || value.reference().calibrationProcessId() == null
				|| value.reference().processAttemptNumber() == null || value.reference().contentHash() == null
				|| value.reference().contentHash().isBlank() || value.cells().size() != value.cellCount()) return false;
		var referenceIdentity = value.reference().calibrationEventId() + ":" + value.reference().curveVersionId() + ":"
				+ value.reference().calibrationProcessId() + ":" + value.reference().processAttemptNumber() + ":"
				+ value.reference().contentHash();
		for (var index = 1; index <= value.cellCount(); index++) {
			var cell = value.cells().get(index);
			if (cell == null || !Double.isFinite(cell.socPct()) || cell.socPct() < 0 || cell.socPct() > 100
					|| cell.currentMeasuredAt() == null || cell.currentMeasuredAt() <= 0
					|| cell.currentMeasuredAt() > value.measurementTimestamp()
					|| !referenceIdentity.equals(cell.referenceIdentity())) return false;
		}
		return true;
	}

	private static SocEstimatorCheckpoint newest(SocEstimatorCheckpoint current,
			SocEstimatorCheckpoint candidate) {
		if (candidate.measurementTimestamp() != current.measurementTimestamp()) {
			return candidate.measurementTimestamp() > current.measurementTimestamp() ? candidate : current;
		}
		return candidate.sourceSequence() >= current.sourceSequence() ? candidate : current;
	}
}
