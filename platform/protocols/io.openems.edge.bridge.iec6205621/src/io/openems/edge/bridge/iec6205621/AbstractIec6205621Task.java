package io.openems.edge.bridge.iec6205621;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

import io.openems.edge.common.taskmanager.Priority;

/** Base class for recurring IEC 62056-21 tasks. */
public abstract class AbstractIec6205621Task implements Iec6205621Task {

	private final Priority priority;
	private final LongUnaryOperator nextExecution;
	private final AtomicBoolean active = new AtomicBoolean(true);
	private volatile Iec6205621Component parent;
	private volatile long nextExecutionTimeMillis;

	protected AbstractIec6205621Task(Priority priority, long firstExecutionTimeMillis,
			LongUnaryOperator nextExecution) {
		this.priority = Objects.requireNonNull(priority);
		this.nextExecutionTimeMillis = firstExecutionTimeMillis;
		this.nextExecution = Objects.requireNonNull(nextExecution);
	}

	@Override
	public final Priority getPriority() {
		return this.priority;
	}

	@Override
	public final void setParent(Iec6205621Component parent) {
		this.parent = parent;
	}

	@Override
	public final Iec6205621Component getParent() {
		return this.parent;
	}

	@Override
	public final long getNextExecutionTimeMillis() {
		return this.nextExecutionTimeMillis;
	}

	@Override
	public final boolean isRecurring() {
		return true;
	}

	@Override
	public final boolean isDue(long nowMillis) {
		return this.active.get() && Iec6205621Task.super.isDue(nowMillis);
	}

	@Override
	public void onExecutionFinished(long finishedAtMillis, Throwable error) {
		if (this.active.get()) {
			this.nextExecutionTimeMillis = this.nextExecution.applyAsLong(finishedAtMillis);
		}
	}

	@Override
	public void deactivate() {
		this.active.set(false);
	}
}
