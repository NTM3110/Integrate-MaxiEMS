package io.openems.edge.bridge.iec6205621;

import io.openems.edge.common.taskmanager.ManagedTask;

/** A unit of IEC 62056-21 work executed exclusively on one physical port. */
public interface Iec6205621Task extends ManagedTask {

	void setParent(Iec6205621Component parent);

	Iec6205621Component getParent();

	long getNextExecutionTimeMillis();

	boolean isRecurring();

	default boolean isDue(long nowMillis) {
		return this.getNextExecutionTimeMillis() <= nowMillis;
	}

	void execute(Iec6205621Transport transport) throws Exception;

	default void onExecutionFinished(long finishedAtMillis, Throwable error) {
	}

	default void deactivate() {
	}
}
