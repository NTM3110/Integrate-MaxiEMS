package io.openems.edge.bridge.iec6205621;

import java.util.Objects;

import io.openems.edge.common.taskmanager.TasksManager;

/** Tasks registered by one component on an IEC 62056-21 Bridge. */
public final class Iec6205621Protocol {

	private final Iec6205621Component parent;
	private final TasksManager<Iec6205621Task> taskManager = new TasksManager<>();

	public Iec6205621Protocol(Iec6205621Component parent, Iec6205621Task... tasks) {
		this.parent = Objects.requireNonNull(parent);
		this.addTasks(tasks);
	}

	public synchronized void addTasks(Iec6205621Task... tasks) {
		for (var task : tasks) {
			this.addTask(task);
		}
	}

	public synchronized void addTask(Iec6205621Task task) {
		task.setParent(this.parent);
		this.taskManager.addTask(task);
	}

	public synchronized void removeTask(Iec6205621Task task) {
		task.deactivate();
		this.taskManager.removeTask(task);
	}

	public Iec6205621Component getParent() {
		return this.parent;
	}

	public TasksManager<Iec6205621Task> getTaskManager() {
		return this.taskManager;
	}

	public void deactivate() {
		this.taskManager.getTasks().forEach(Iec6205621Task::deactivate);
	}
}
