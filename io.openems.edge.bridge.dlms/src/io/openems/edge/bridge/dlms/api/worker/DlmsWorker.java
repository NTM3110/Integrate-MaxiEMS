package io.openems.edge.bridge.dlms.api.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.worker.AbstractImmediateWorker;
import io.openems.edge.bridge.dlms.api.DlmsComponent;
import io.openems.edge.bridge.dlms.api.DlmsProtocol;
import io.openems.edge.bridge.dlms.api.task.DlmsTask;
import io.openems.edge.bridge.dlms.api.task.DlmsTask.ExecuteState;
import io.openems.edge.bridge.dlms.api.task.ReadProfileTask;
import io.openems.edge.common.taskmanager.TasksManager;
public class DlmsWorker extends AbstractImmediateWorker {

	private final Logger log = LoggerFactory.getLogger(DlmsWorker.class);
	private final Function<DlmsTask, ExecuteState> execute;
	private final Map<String, DlmsProtocol> protocols = new HashMap<>();
	private final TasksManager<DlmsTask> taskManager = new TasksManager<>();
	private final Queue<DlmsTask> oneShotTasks = new ConcurrentLinkedQueue<>();
	private volatile int cycleDelay = 0;

	public DlmsWorker(Function<DlmsTask, ExecuteState> execute) {
		this.execute = execute;
	}

	public void setCycleDelay(int delayMs) {
		this.cycleDelay = delayMs;
	}

	@Override
	protected void forever() throws InterruptedException {
		DlmsTask task = this.oneShotTasks.poll();
		if (task == null) {
			task = this.taskManager.getOneTask();
		}
		if (task == null) {
			Thread.sleep(100);
			return;
		}
		if (task instanceof ReadProfileTask profileTask && !profileTask.isDue()) {
			Thread.sleep(1000);
			return;
		}

		var taskName = task.getClass().getSimpleName();
		var parentId = task.getParent() != null ? task.getParent().id() : "-";
		this.log.info("Starting DLMS task [{}] for component [{}]; queued one-shot tasks [{}]", taskName, parentId,
				this.oneShotTasks.size());
		try {
			ExecuteState result = this.execute.apply(task);
			if (result instanceof ExecuteState.Ok) {
				this.markComponentAsDefective(task.getParent(), false);
			} else if (result instanceof ExecuteState.Error) {
				this.markComponentAsDefective(task.getParent(), true);
			}
			this.log.info("Finished DLMS task [{}] for component [{}]", taskName, parentId);
		} catch (Throwable e) {
			this.log.error("DLMS task [{}] for component [{}] crashed: {}", taskName, parentId, e.getMessage(), e);
			this.markComponentAsDefective(task.getParent(), true);
		}

		// Apply configured delay between task cycles
		if (this.cycleDelay > 0) {
			Thread.sleep(this.cycleDelay);
		}
	}

	private void markComponentAsDefective(DlmsComponent component, boolean isDefective) {
		if (component != null) {
			component._setDlmsCommunicationFailed(isDefective);
		}
	}

	public synchronized void addProtocol(String sourceId, DlmsProtocol protocol) {
		this.protocols.put(sourceId, protocol);
		this.updateTaskManager();
	}

	public synchronized void removeProtocol(String sourceId) {
		this.protocols.remove(sourceId);
		this.updateTaskManager();
	}

	private void updateTaskManager() {
		this.taskManager.clearAll();
		for (DlmsProtocol protocol : this.protocols.values()) {
			this.taskManager.addTasks(protocol.getTaskManager().getTasks());
		}
	}

	public void retryDlmsCommunication(String sourceId) {
		// For now, doing nothing special here
	}

	public void submitOneShot(DlmsTask task) {
		this.oneShotTasks.add(task);
		this.log.info("Queued one-shot DLMS task [{}] for component [{}]; queue size [{}]",
				task.getClass().getSimpleName(), task.getParent() != null ? task.getParent().id() : "-",
				this.oneShotTasks.size());
		this.triggerNextRun();
	}

	public boolean isRunning() {
		return this.thread.isAlive() && !this.thread.isInterrupted();
	}

	public boolean isWorkerThread() {
		return Thread.currentThread() == this.thread;
	}
}
