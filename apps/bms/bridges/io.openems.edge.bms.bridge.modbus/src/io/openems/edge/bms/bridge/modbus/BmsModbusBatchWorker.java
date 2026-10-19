package io.openems.edge.bms.bridge.modbus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.CompletedBatch;
import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.Outcome;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.task.ReadTask;
import io.openems.edge.bridge.modbus.api.task.Task;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.bridge.modbus.api.task.WriteTask;

/** One serial-port worker: registered components are read in stable ID order. */
final class BmsModbusBatchWorker {
	static final long PROCESSING_MS = 10_000;
	private static final Logger LOG = LoggerFactory.getLogger(BmsModbusBatchWorker.class);

	private record Registration(ModbusProtocol protocol, List<Task> tasks) {
	}

	private static final class RetryState {
		private int failures;
		private long nextEligibleCycle = 1;
	}

	private final Map<String, Registration> registrations = new TreeMap<>();
	private final Map<String, RetryState> retryStates = new TreeMap<>();
	private String generation = UUID.randomUUID().toString();
	private final Function<Task, ExecuteState> execute;
	private final Consumer<ModbusElement[]> invalidate;
	private final IntSupplier invalidateAfterErrors;
	private final LongSupplier clock;
	private final Consumer<CompletedBatch> completed;
	private final Predicate<CompletedBatch> acknowledged;
	private volatile boolean running;
	private volatile boolean cancellationRequested;
	private volatile boolean waitingForAck;
	private volatile String failure;
	private Thread thread;
	private String bridgeId = "unknown";
	private long cycleId;

	BmsModbusBatchWorker(Function<Task, ExecuteState> execute, Consumer<ModbusElement[]> invalidate,
			IntSupplier invalidateAfterErrors, LongSupplier clock, Consumer<CompletedBatch> completed,
			Predicate<CompletedBatch> acknowledged) {
		this.execute = execute;
		this.invalidate = invalidate;
		this.invalidateAfterErrors = invalidateAfterErrors;
		this.clock = clock;
		this.completed = completed;
		this.acknowledged = acknowledged;
	}

	synchronized void addProtocol(String sourceId, ModbusProtocol protocol) {
		var tasks = List.copyOf(protocol.getTaskManager().getTasks());
		if (tasks.stream().anyMatch(task -> !(task instanceof ReadTask) || task instanceof WriteTask)) {
			throw new IllegalArgumentException("BMS batch bridge accepts read-only protocols: " + sourceId);
		}
		this.registrations.put(sourceId, new Registration(protocol, tasks));
		this.retryStates.remove(sourceId);
	}

	synchronized void removeProtocol(String sourceId) {
		var registration = this.registrations.remove(sourceId);
		this.retryStates.remove(sourceId);
		if (registration != null) {
			registration.tasks.forEach(task -> this.invalidate.accept(task.getElements()));
		}
	}

	synchronized void retry(String sourceId) {
		this.retryStates.remove(sourceId);
	}

	private synchronized Map<String, Registration> snapshot() {
		return new LinkedHashMap<>(this.registrations);
	}

	private synchronized RetryState retryState(String sourceId) {
		return this.retryStates.computeIfAbsent(sourceId, ignore -> new RetryState());
	}

	CompletedBatch executeBatch() {
		var startedAt = this.clock.getAsLong();
		var snapshot = this.snapshot();
		if (snapshot.isEmpty()) {
			return null;
		}
		var cycle = ++this.cycleId;
		LOG.info("BMS batch read started: bridge={} generation={} batchId={} components={}",
				this.bridgeId, this.generation, cycle, snapshot.size());
		var outcomes = new LinkedHashMap<String, Outcome>();
		for (var entry : snapshot.entrySet()) {
			if (this.cancellationRequested) {
				LOG.info("BMS batch read cancelled: bridge={} generation={} batchId={} completedComponents={}",
						this.bridgeId, this.generation, cycle, outcomes.size());
				return null;
			}
			var sourceId = entry.getKey();
			var retry = this.retryState(sourceId);
			if (cycle < retry.nextEligibleCycle) {
				outcomes.put(sourceId, Outcome.SKIPPED);
				continue;
			}
			var tasks = new ArrayList<>(entry.getValue().tasks);
			tasks.sort(Comparator.comparingInt(Task::getStartAddress));
			var outcome = Outcome.SUCCESS;
			for (var task : tasks) {
				ExecuteState result;
				try {
					result = this.execute.apply(task);
				} catch (Exception e) {
					result = new ExecuteState.Error(e);
				}
				if (!(result instanceof ExecuteState.Ok)) {
					outcome = Outcome.FAILED;
					break;
				}
			}
			if (outcome == Outcome.SUCCESS) {
				retry.failures = 0;
				retry.nextEligibleCycle = cycle + 1;
			} else {
				retry.failures++;
				var waitCycles = retry.failures == 1 ? 1 : retry.failures == 2 ? 2 : 10;
				retry.nextEligibleCycle = cycle + waitCycles;
				if (retry.failures >= this.invalidateAfterErrors.getAsInt()) {
					tasks.forEach(task -> this.invalidate.accept(task.getElements()));
				}
			}
			for (var task : tasks) {
				if (task.getParent() != null) {
					task.getParent()._setModbusCommunicationFailed(outcome == Outcome.FAILED);
					break;
				}
			}
			outcomes.put(sourceId, outcome);
		}
		var completedAt = this.clock.getAsLong();
		var failedIds = outcomes.entrySet().stream()
				.filter(entry -> entry.getValue() == Outcome.FAILED).map(Map.Entry::getKey).toList();
		LOG.info("BMS batch read completed: bridge={} generation={} batchId={} durationMs={} success={} failed={} skipped={} failedIds={}",
				this.bridgeId, this.generation, cycle, completedAt - startedAt,
				outcomes.values().stream().filter(outcome -> outcome == Outcome.SUCCESS).count(),
				failedIds.size(), outcomes.values().stream().filter(outcome -> outcome == Outcome.SKIPPED).count(),
				failedIds);
		return new CompletedBatch(this.generation, cycle, startedAt, completedAt, outcomes);
	}

	synchronized void start(String id) {
		if (this.running) {
			return;
		}
		if (this.thread != null && this.thread.isAlive()) {
			throw new IllegalStateException("Previous BMS Modbus batch worker is still using the serial port");
		}
		this.running = true;
		this.cancellationRequested = false;
		this.waitingForAck = false;
		this.failure = null;
		this.generation = UUID.randomUUID().toString();
		this.bridgeId = id;
		this.cycleId = 0;
		this.thread = new Thread(this::run, "bms-modbus-batch-" + id);
		this.thread.start();
	}

	void stop() {
		Thread old;
		synchronized (this) {
			this.running = false;
			this.cancellationRequested = true;
			old = this.thread;
			if (old != null) {
				old.interrupt();
			}
		}
		if (old != null && old != Thread.currentThread()) {
			try {
				old.join(15_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while stopping BMS Modbus batch worker", e);
			}
			if (old.isAlive()) {
				throw new IllegalStateException("BMS Modbus batch worker is still using the serial port");
			}
		}
	}

	boolean waitingForAck() {
		return this.waitingForAck;
	}

	String failure() {
		return this.failure;
	}

	private void run() {
		try {
			while (this.running) {
				var batch = this.executeBatch();
				if (!this.running) return;
				if (batch == null) {
					if (!this.pause(250)) return;
					continue;
				}
				this.completed.accept(batch);
				// No request from the next batch may overwrite a channel until the
				// calculator has consumed this batch, even if Core.Cycle is delayed.
				while (this.running && (!this.acknowledged.test(batch)
						|| this.clock.getAsLong() < batch.completedAt() + PROCESSING_MS)) {
					this.waitingForAck = !this.acknowledged.test(batch);
					if (!this.pause(100)) return;
				}
				this.waitingForAck = false;
			}
		} catch (RuntimeException e) {
			this.failure = e.getClass().getSimpleName() + ": " + e.getMessage();
			this.running = false;
		} finally {
			this.waitingForAck = false;
		}
	}

	private boolean pause(long millis) {
		try {
			Thread.sleep(millis);
			return this.running;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
