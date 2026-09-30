package io.openems.edge.bridge.iec6205621;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.worker.AbstractWorker;
import io.openems.edge.common.taskmanager.Priority;

/** Serializes and schedules all IEC 62056-21 work for one physical port. */
public final class Iec6205621Worker extends AbstractWorker {

	@FunctionalInterface
	public interface TaskExecutor {
		void execute(Iec6205621Task task) throws Exception;
	}

	private static final int MAX_CONSECUTIVE_ONE_SHOTS = 4;
	private static final int IDLE_POLL_MILLIS = 250;

	private record QueuedTask(String sourceId, Iec6205621Task task) {
	}

	private final Logger log = LoggerFactory.getLogger(Iec6205621Worker.class);
	private final TaskExecutor executor;
	private final Map<String, Iec6205621Protocol> protocols = new LinkedHashMap<>();
	private final Queue<QueuedTask> oneShotTasks = new ConcurrentLinkedQueue<>();
	private int nextProtocolIndex;
	private int consecutiveOneShots;

	public Iec6205621Worker(TaskExecutor executor) {
		this.executor = Objects.requireNonNull(executor);
	}

	@Override
	protected int getCycleTime() {
		return IDLE_POLL_MILLIS;
	}

	@Override
	protected void forever() {
		var selection = this.selectNextTask(System.currentTimeMillis());
		if (selection == null) {
			return;
		}

		var task = selection.task();
		var parent = task.getParent();
		Throwable error = null;
		try {
			this.executor.execute(task);
			if (parent != null) {
				parent._setIec6205621CommunicationFailed(false);
			}
		} catch (Throwable e) {
			error = e;
			if (parent != null) {
				parent._setIec6205621CommunicationFailed(true);
			}
			this.log.warn("IEC 62056-21 task [{}] for component [{}] failed: {}",
					task.getClass().getSimpleName(), selection.sourceId(), e.getMessage());
		} finally {
			task.onExecutionFinished(System.currentTimeMillis(), error);
		}
	}

	private synchronized QueuedTask selectNextTask(long nowMillis) {
		var highRecurring = this.selectRecurring(nowMillis, Priority.HIGH);
		if (highRecurring != null) {
			this.consecutiveOneShots = 0;
			return highRecurring;
		}

		if (this.consecutiveOneShots < MAX_CONSECUTIVE_ONE_SHOTS) {
			var oneShot = this.oneShotTasks.poll();
			if (oneShot != null) {
				this.consecutiveOneShots++;
				return oneShot;
			}
		}

		var recurring = this.selectRecurring(nowMillis, Priority.LOW);
		if (recurring != null) {
			this.consecutiveOneShots = 0;
			return recurring;
		}

		var oneShot = this.oneShotTasks.poll();
		if (oneShot != null) {
			this.consecutiveOneShots = 1;
			return oneShot;
		}
		this.consecutiveOneShots = 0;
		return null;
	}

	private QueuedTask selectRecurring(long nowMillis, Priority priority) {
		if (this.protocols.isEmpty()) {
			return null;
		}
		var entries = new ArrayList<>(this.protocols.entrySet());
		for (int offset = 0; offset < entries.size(); offset++) {
			int index = (this.nextProtocolIndex + offset) % entries.size();
			var entry = entries.get(index);
			Iec6205621Task selected = null;
			for (var task : entry.getValue().getTaskManager().getTasks(priority)) {
				if (task.isRecurring() && task.isDue(nowMillis)
						&& (selected == null || task.getNextExecutionTimeMillis() < selected.getNextExecutionTimeMillis())) {
					selected = task;
				}
			}
			if (selected != null) {
				this.nextProtocolIndex = (index + 1) % entries.size();
				return new QueuedTask(entry.getKey(), selected);
			}
		}
		return null;
	}

	public synchronized void addProtocol(String sourceId, Iec6205621Protocol protocol) {
		Objects.requireNonNull(sourceId);
		Objects.requireNonNull(protocol);
		var previous = this.protocols.put(sourceId, protocol);
		if (previous != null) {
			previous.deactivate();
		}
		this.triggerNextRun();
	}

	public synchronized void removeProtocol(String sourceId) {
		var protocol = this.protocols.remove(sourceId);
		if (protocol != null) {
			protocol.deactivate();
		}
		var removed = new ArrayList<QueuedTask>();
		this.oneShotTasks.removeIf(queued -> {
			if (queued.sourceId().equals(sourceId)) {
				removed.add(queued);
				return true;
			}
			return false;
		});
		removed.forEach(queued -> queued.task().deactivate());
		if (this.nextProtocolIndex >= this.protocols.size()) {
			this.nextProtocolIndex = 0;
		}
	}

	public synchronized void submitOneShot(String sourceId, Iec6205621Task task) {
		Objects.requireNonNull(sourceId);
		Objects.requireNonNull(task);
		var protocol = this.protocols.get(sourceId);
		if (protocol == null) {
			throw new IllegalStateException("No IEC 62056-21 protocol registered for component [" + sourceId + "]");
		}
		task.setParent(protocol.getParent());
		this.oneShotTasks.add(new QueuedTask(sourceId, task));
		this.triggerNextRun();
	}

	public void submitCompatibilityOneShot(Iec6205621Task task) {
		this.oneShotTasks.add(new QueuedTask("-", task));
		this.triggerNextRun();
	}

	public synchronized List<String> getRegisteredSourceIds() {
		return List.copyOf(this.protocols.keySet());
	}

	public boolean isWorkerThread() {
		return Thread.currentThread() == this.thread;
	}

	@Override
	public synchronized void deactivate() {
		this.protocols.values().forEach(Iec6205621Protocol::deactivate);
		this.protocols.clear();
		this.oneShotTasks.forEach(queued -> queued.task().deactivate());
		this.oneShotTasks.clear();
		super.deactivate();
	}
}
