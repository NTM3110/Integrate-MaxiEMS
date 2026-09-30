package io.openems.edge.bridge.iec6205621;

import java.util.concurrent.CompletableFuture;

import io.openems.edge.common.component.OpenemsComponent;

/** Serial bridge for IEC 62056-21 meters. */
public interface Iec6205621Bridge extends OpenemsComponent {

	void addProtocol(String sourceId, Iec6205621Protocol protocol);

	void removeProtocol(String sourceId);

	<T> CompletableFuture<T> execute(String sourceId, Iec6205621Transaction<T> transaction);

	/** Compatibility adapter for callers not yet represented by a component protocol. */
	<T> CompletableFuture<T> execute(Iec6205621Transaction<T> transaction);
}
