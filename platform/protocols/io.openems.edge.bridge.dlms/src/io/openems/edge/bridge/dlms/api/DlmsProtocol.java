package io.openems.edge.bridge.dlms.api;

import io.openems.edge.bridge.dlms.api.task.DlmsTask;
import io.openems.edge.common.taskmanager.TasksManager;

public class DlmsProtocol {

	private final DlmsComponent parent;
	private final TasksManager<DlmsTask> taskManager = new TasksManager<>();

	public DlmsProtocol(DlmsComponent parent, DlmsTask... tasks) {
		this.parent = parent;
		this.addTasks(tasks);
	}

	public synchronized void addTasks(DlmsTask... tasks) {
		for (DlmsTask task : tasks) {
			this.addTask(task);
		}
	}

	public synchronized void addTask(DlmsTask task) {
		task.setParent(this.parent);
		this.taskManager.addTask(task);
	}

	public synchronized void removeTask(DlmsTask task) {
		this.taskManager.removeTask(task);
	}

	public TasksManager<DlmsTask> getTaskManager() {
		return this.taskManager;
	}

	public void deactivate() {
		this.taskManager.getTasks().forEach(DlmsTask::deactivate);
	}
}
