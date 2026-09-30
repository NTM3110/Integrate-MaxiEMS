package io.openems.edge.network.manager.jsonrpc;

import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.operation.NetworkOperationSnapshot;

public final class ConfirmNetworkManagerConfig
		implements EndpointRequestType<ConfirmNetworkManagerConfig.Request, NetworkOperationSnapshot> {
	@Override
	public String getMethod() {
		return "confirmNetworkManagerConfig";
	}

	@Override
	public JsonSerializer<Request> getRequestSerializer() {
		return Request.serializer();
	}

	@Override
	public JsonSerializer<NetworkOperationSnapshot> getResponseSerializer() {
		return NetworkOperationSnapshot.serializer();
	}

	public record Request(String operationId, long expectedSequence) {
		public static JsonSerializer<Request> serializer() {
			return JsonSerializerUtil.jsonObjectSerializer(Request.class,
					json -> new Request(json.getString("operationId"), json.getLong("expectedSequence")),
					obj -> JsonUtils.buildJsonObject().addProperty("operationId", obj.operationId())
							.addProperty("expectedSequence", obj.expectedSequence()).build());
		}
	}
}
