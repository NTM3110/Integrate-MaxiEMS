package io.openems.edge.bridge.edmi.api;

import io.openems.edge.common.taskmanager.Priority;

public abstract class AbstractEdmiTask implements EdmiTask {

	private final Priority priority;
	private long nextRunTime = 0;

	public AbstractEdmiTask(Priority priority) {
		this.priority = priority;
	}

	@Override
	public Priority getPriority() {
		return this.priority;
	}

	@Override
	public long getNextRunTime() {
		return this.nextRunTime;
	}

	@Override
	public void setNextRunTime(long nextRunTime) {
		this.nextRunTime = nextRunTime;
	}
}
