package io.openems.edge.network.manager.jsonrpc;

import java.util.List;

import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;
import io.openems.edge.network.manager.operation.NetworkOperationSnapshot;

public final class SetNetworkManagerConfig
		implements EndpointRequestType<SetNetworkManagerConfig.Request, NetworkOperationSnapshot> {

	public static final String METHOD = "setNetworkManagerConfig";

	@Override
	public String getMethod() {
		return METHOD;
	}

	@Override
	public JsonSerializer<Request> getRequestSerializer() {
		return Request.serializer();
	}

	@Override
	public JsonSerializer<NetworkOperationSnapshot> getResponseSerializer() {
		return NetworkOperationSnapshot.serializer();
	}

	public record Request(String interfaceName, Mode mode, String ip, Integer prefixLength, String gateway,
			List<String> dns, long expectedRevision) {

		public Request {
			dns = List.copyOf(dns);
		}

		public NetworkInterfaceConfig toConfig() {
			return new NetworkInterfaceConfig(this.interfaceName, this.mode, this.ip, this.prefixLength, this.gateway,
					this.dns);
		}

		public static JsonSerializer<Request> serializer() {
			var strings = JsonSerializerUtil.stringSerializer().toListSerializer();
			return JsonSerializerUtil.jsonObjectSerializer(Request.class,
					json -> new Request(json.getString("interfaceName"), json.getEnum("mode", Mode.class),
							json.getOptionalString("ip").orElse(null),
							json.getOptionalLong("prefixLength").map(Long::intValue).orElse(null),
							json.getOptionalString("gateway").orElse(null),
							json.getList("dns", JsonSerializerUtil.stringSerializer()), json.getLong("expectedRevision")),
					obj -> JsonUtils.buildJsonObject().addProperty("interfaceName", obj.interfaceName())
							.addProperty("mode", obj.mode()).addPropertyIfNotNull("ip", obj.ip())
							.addPropertyIfNotNull("prefixLength", obj.prefixLength())
							.addPropertyIfNotNull("gateway", obj.gateway()).add("dns", strings.serialize(obj.dns()))
							.addProperty("expectedRevision", obj.expectedRevision()).build());
		}
	}
}
