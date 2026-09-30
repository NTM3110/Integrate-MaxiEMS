package io.openems.edge.bridge.iec6205621;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import io.openems.edge.common.taskmanager.Priority;

/** Request-scoped transaction that is executed by the Bridge worker. */
public final class Iec6205621OneShotTask<T> implements Iec6205621Task {

	private final Priority priority;
	private final Iec6205621Transaction<T> transaction;
	private final CompletableFuture<T> future = new CompletableFuture<>();
	private volatile Iec6205621Component parent;
	private T result;

	public Iec6205621OneShotTask(Priority priority, Iec6205621Transaction<T> transaction) {
		this.priority = Objects.requireNonNull(priority);
		this.transaction = Objects.requireNonNull(transaction);
	}

	@Override
	public Priority getPriority() {
		return this.priority;
	}

	@Override
	public void setParent(Iec6205621Component parent) {
		this.parent = parent;
	}

	@Override
	public Iec6205621Component getParent() {
		return this.parent;
	}

	@Override
	public long getNextExecutionTimeMillis() {
		return Long.MIN_VALUE;
	}

	@Override
	public boolean isRecurring() {
		return false;
	}

	@Override
	public void execute(Iec6205621Transport transport) throws Exception {
		this.result = this.transaction.execute(transport);
	}

	@Override
	public void onExecutionFinished(long finishedAtMillis, Throwable error) {
		if (error == null) {
			this.future.complete(this.result);
		} else {
			this.future.completeExceptionally(error);
		}
		this.result = null;
	}

	@Override
	public void deactivate() {
		this.future.completeExceptionally(new CancellationException("IEC 62056-21 task was removed"));
	}

	public CompletableFuture<T> future() {
		return this.future;
	}
}
