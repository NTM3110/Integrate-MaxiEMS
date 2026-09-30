package io.openems.edge.meter.elster.a1700;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

import io.openems.edge.bridge.iec6205621.AbstractIec6205621Task;
import io.openems.edge.bridge.iec6205621.Iec6205621Transport;
import io.openems.edge.common.taskmanager.Priority;

/** A recurring, model-specific A1700 operation scheduled by the IEC Bridge. */
final class A1700ReadTask extends AbstractIec6205621Task {

	@FunctionalInterface
	interface Operation {
		void execute(Iec6205621Transport transport) throws Exception;
	}

	private final String name;
	private final Operation operation;
	private final Consumer<Throwable> onError;

	A1700ReadTask(String name, Priority priority, long firstExecutionTimeMillis, LongUnaryOperator nextExecution,
			Operation operation, Consumer<Throwable> onError) {
		super(priority, firstExecutionTimeMillis, nextExecution);
		this.name = Objects.requireNonNull(name);
		this.operation = Objects.requireNonNull(operation);
		this.onError = Objects.requireNonNull(onError);
	}

	@Override
	public void execute(Iec6205621Transport transport) throws Exception {
		try {
			this.operation.execute(transport);
		} catch (Exception e) {
			this.onError.accept(e);
			throw e;
		}
	}

	@Override
	public String toString() {
		return this.name;
	}
}
