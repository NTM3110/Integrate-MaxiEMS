package io.openems.edge.network.manager.operation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;

public record NetworkOperationSnapshot(String operationId, long sequence, long revision, NetworkOperationState state,
		NetworkInterfaceConfig desired, Instant createdAt, Instant updatedAt, int confirmTimeoutSeconds,
		Instant confirmationDeadline, String checkpointId, List<String> previousAddresses, List<String> candidateAddresses,
		boolean profileVerified, boolean runtimeVerified, boolean linkAvailable, NetworkOperationError error) {

	public NetworkOperationSnapshot {
		Objects.requireNonNull(operationId);
		Objects.requireNonNull(state);
		Objects.requireNonNull(desired);
		Objects.requireNonNull(createdAt);
		Objects.requireNonNull(updatedAt);
		previousAddresses = List.copyOf(previousAddresses);
		candidateAddresses = List.copyOf(candidateAddresses);
	}

	public String interfaceName() {
		return this.desired.interfaceName();
	}

	public NetworkOperationSnapshot transition(NetworkOperationState nextState, Instant at) {
		if (this.state.isTerminal()) {
			throw new IllegalStateException("Operation is already terminal: " + this.operationId);
		}
		return new NetworkOperationSnapshot(this.operationId, this.sequence + 1, this.revision, nextState, this.desired,
				this.createdAt, at, this.confirmTimeoutSeconds, this.confirmationDeadline, this.checkpointId, this.previousAddresses,
				this.candidateAddresses, this.profileVerified, this.runtimeVerified, this.linkAvailable, this.error);
	}

	public NetworkOperationSnapshot fail(NetworkOperationError operationError, Instant at) {
		var failed = this.transition(NetworkOperationState.FAILED, at);
		return new NetworkOperationSnapshot(failed.operationId, failed.sequence, failed.revision, failed.state,
				failed.desired, failed.createdAt, failed.updatedAt, failed.confirmTimeoutSeconds,
				failed.confirmationDeadline, failed.checkpointId, failed.previousAddresses, failed.candidateAddresses, failed.profileVerified,
				failed.runtimeVerified, failed.linkAvailable, operationError);
	}

	public NetworkOperationSnapshot verified(boolean isProfileVerified, boolean isRuntimeVerified,
			boolean isLinkAvailable, Instant deadline, Instant at) {
		if (this.state.isTerminal()) {
			throw new IllegalStateException("Operation is already terminal: " + this.operationId);
		}
		var nextState = isLinkAvailable ? NetworkOperationState.AWAITING_CONFIRMATION
				: NetworkOperationState.CONFIGURED_WAITING_FOR_LINK;
		return new NetworkOperationSnapshot(this.operationId, this.sequence + 1, this.revision, nextState, this.desired,
				this.createdAt, at, this.confirmTimeoutSeconds, deadline, this.checkpointId, this.previousAddresses,
				this.candidateAddresses, isProfileVerified, isRuntimeVerified, isLinkAvailable, this.error);
	}

	public NetworkOperationSnapshot withCheckpoint(String newCheckpointId, Instant deadline, Instant at) {
		if (this.state.isTerminal()) {
			throw new IllegalStateException("Operation is already terminal: " + this.operationId);
		}
		return new NetworkOperationSnapshot(this.operationId, this.sequence + 1, this.revision, this.state, this.desired,
				this.createdAt, at, this.confirmTimeoutSeconds, deadline, newCheckpointId, this.previousAddresses,
				this.candidateAddresses, this.profileVerified, this.runtimeVerified, this.linkAvailable, this.error);
	}

	public NetworkOperationSnapshot rolledBack(NetworkOperationError operationError, Instant at) {
		var rolledBack = this.transition(NetworkOperationState.ROLLED_BACK, at);
		return new NetworkOperationSnapshot(rolledBack.operationId, rolledBack.sequence, rolledBack.revision,
				rolledBack.state, rolledBack.desired, rolledBack.createdAt, rolledBack.updatedAt,
				rolledBack.confirmTimeoutSeconds, rolledBack.confirmationDeadline, rolledBack.checkpointId,
				rolledBack.previousAddresses, rolledBack.candidateAddresses, rolledBack.profileVerified,
				rolledBack.runtimeVerified, rolledBack.linkAvailable, operationError);
	}

	public static JsonSerializer<NetworkOperationSnapshot> serializer() {
		return serializer(false);
	}

	public static JsonSerializer<NetworkOperationSnapshot> persistenceSerializer() {
		return serializer(true);
	}

	private static JsonSerializer<NetworkOperationSnapshot> serializer(boolean includeCheckpointId) {
		var strings = JsonSerializerUtil.stringSerializer().toListSerializer();
		return JsonSerializerUtil.jsonObjectSerializer(NetworkOperationSnapshot.class,
				json -> new NetworkOperationSnapshot(json.getString("operationId"), json.getLong("sequence"),
						json.getLong("revision"), json.getEnum("state", NetworkOperationState.class),
						json.getObject("desired", NetworkInterfaceConfig.serializer()),
						Instant.parse(json.getString("createdAt")), Instant.parse(json.getString("updatedAt")),
							(int) json.getLong("confirmTimeoutSeconds"),
							json.getOptionalString("confirmationDeadline").map(Instant::parse).orElse(null),
							json.getOptionalString("checkpointId").orElse(null),
						json.getList("previousAddresses", JsonSerializerUtil.stringSerializer()),
						json.getList("candidateAddresses", JsonSerializerUtil.stringSerializer()),
						json.getBoolean("profileVerified"), json.getBoolean("runtimeVerified"),
						json.getBoolean("linkAvailable"),
						json.getObjectOrNull("error", NetworkOperationError.serializer())),
				obj -> JsonUtils.buildJsonObject().addProperty("operationId", obj.operationId())
						.addProperty("sequence", obj.sequence()).addProperty("revision", obj.revision())
						.addProperty("state", obj.state()).add("desired", NetworkInterfaceConfig.serializer().serialize(obj.desired()))
						.addProperty("interfaceName", obj.interfaceName()).addProperty("createdAt", obj.createdAt())
						.addProperty("updatedAt", obj.updatedAt())
						.addProperty("confirmTimeoutSeconds", obj.confirmTimeoutSeconds())
							.addPropertyIfNotNull("confirmationDeadline",
									obj.confirmationDeadline() == null ? null : obj.confirmationDeadline().toString())
								.onlyIf(includeCheckpointId,
										json -> json.addPropertyIfNotNull("checkpointId", obj.checkpointId()))
						.add("previousAddresses", strings.serialize(obj.previousAddresses()))
						.add("candidateAddresses", strings.serialize(obj.candidateAddresses()))
						.addProperty("profileVerified", obj.profileVerified())
						.addProperty("runtimeVerified", obj.runtimeVerified())
						.addProperty("linkAvailable", obj.linkAvailable())
						.addIfNotNull("error", obj.error() == null ? null : NetworkOperationError.serializer().serialize(obj.error()).getAsJsonObject())
						.build());
	}
}
