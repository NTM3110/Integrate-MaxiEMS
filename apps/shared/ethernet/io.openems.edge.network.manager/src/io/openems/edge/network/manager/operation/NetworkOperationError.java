package io.openems.edge.network.manager.operation;

import java.util.Objects;

import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;

public record NetworkOperationError(NetworkErrorCode code, NetworkOperationStage stage, String message,
		boolean retryable, NetworkOperationState rollbackState) {

	public NetworkOperationError {
		Objects.requireNonNull(code);
		Objects.requireNonNull(stage);
		Objects.requireNonNull(message);
	}

	public static JsonSerializer<NetworkOperationError> serializer() {
		return JsonSerializerUtil.jsonObjectSerializer(NetworkOperationError.class,
				json -> new NetworkOperationError(json.getEnum("code", NetworkErrorCode.class),
						json.getEnum("stage", NetworkOperationStage.class), json.getString("message"),
						json.getBoolean("retryable"),
						json.getEnumOrNull("rollbackState", NetworkOperationState.class)),
				obj -> JsonUtils.buildJsonObject().addProperty("code", obj.code()).addProperty("stage", obj.stage())
						.addProperty("message", obj.message()).addProperty("retryable", obj.retryable())
						.addPropertyIfNotNull("rollbackState", obj.rollbackState()).build());
	}
}
