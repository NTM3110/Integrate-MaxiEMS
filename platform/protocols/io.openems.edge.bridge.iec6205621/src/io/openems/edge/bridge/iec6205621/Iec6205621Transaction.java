package io.openems.edge.bridge.iec6205621;

/** A serial transaction that owns the bridge transport until it returns. */
@FunctionalInterface
public interface Iec6205621Transaction<T> {
	T execute(Iec6205621Transport transport) throws Exception;
}
