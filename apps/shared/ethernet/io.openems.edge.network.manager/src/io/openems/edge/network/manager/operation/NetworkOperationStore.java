package io.openems.edge.network.manager.operation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class NetworkOperationStore {

	private final Map<String, NetworkOperationSnapshot> operations = new LinkedHashMap<>();
	private final Consumer<NetworkOperationSnapshot> listener;
	private String activeOperationId;

	public NetworkOperationStore() {
		this(snapshot -> { });
	}

	public NetworkOperationStore(Consumer<NetworkOperationSnapshot> listener) {
		this.listener = listener;
	}

	public synchronized NetworkOperationSnapshot create(String operationId, long revision,
			NetworkInterfaceConfig desired, Instant createdAt, List<String> previousAddresses,
			int confirmTimeoutSeconds) {
		if (this.activeOperationId != null) {
			throw new IllegalStateException(NetworkErrorCode.OPERATION_IN_PROGRESS.name() + ": " + this.activeOperationId);
		}
		if (this.operations.containsKey(operationId)) {
			throw new IllegalArgumentException("Duplicate operation id: " + operationId);
		}
		var snapshot = new NetworkOperationSnapshot(operationId, 1, revision, NetworkOperationState.ACCEPTED, desired,
				createdAt, createdAt, confirmTimeoutSeconds, null, null, previousAddresses, desired.candidateAddresses(), false,
				false, false, null);
		this.listener.accept(snapshot);
		this.operations.put(operationId, snapshot);
		this.activeOperationId = operationId;
		return snapshot;
	}

	public synchronized NetworkOperationSnapshot get(String operationId) {
		var snapshot = this.operations.get(operationId);
		if (snapshot == null) {
			throw new IllegalArgumentException(NetworkErrorCode.OPERATION_NOT_FOUND.name() + ": " + operationId);
		}
		return snapshot;
	}

	public synchronized NetworkOperationSnapshot restore(NetworkOperationSnapshot snapshot) {
		if (this.activeOperationId != null) {
			throw new IllegalStateException("Network operation already in progress: " + this.activeOperationId);
		}
		this.listener.accept(snapshot);
		this.operations.put(snapshot.operationId(), snapshot);
		if (!snapshot.state().isTerminal()) {
			this.activeOperationId = snapshot.operationId();
		}
		return snapshot;
	}

	public synchronized NetworkOperationSnapshot transition(String operationId, NetworkOperationState state,
			Instant at) {
		return this.update(operationId, this.get(operationId).transition(state, at));
	}

	public synchronized NetworkOperationSnapshot fail(String operationId, NetworkOperationError error, Instant at) {
		return this.update(operationId, this.get(operationId).fail(error, at));
	}

	public synchronized NetworkOperationSnapshot rolledBack(String operationId, NetworkOperationError error, Instant at) {
		return this.update(operationId, this.get(operationId).rolledBack(error, at));
	}

	public synchronized NetworkOperationSnapshot verified(String operationId, boolean profileVerified,
			boolean runtimeVerified, boolean linkAvailable, Instant deadline, Instant at) {
		return this.update(operationId,
				this.get(operationId).verified(profileVerified, runtimeVerified, linkAvailable, deadline, at));
	}

	public synchronized NetworkOperationSnapshot withCheckpoint(String operationId, String checkpointId,
			Instant deadline, Instant at) {
		return this.update(operationId, this.get(operationId).withCheckpoint(checkpointId, deadline, at));
	}

	private NetworkOperationSnapshot update(String operationId, NetworkOperationSnapshot snapshot) {
		this.listener.accept(snapshot);
		this.operations.put(operationId, snapshot);
		if (snapshot.state().isTerminal() && operationId.equals(this.activeOperationId)) {
			this.activeOperationId = null;
		}
		return snapshot;
	}
}
