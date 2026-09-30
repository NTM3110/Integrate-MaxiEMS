package io.openems.edge.network.manager.jsonrpc;

import java.util.List;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.jsonrpc.serialization.EndpointRequestType;
import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;
import io.openems.edge.network.manager.operation.NetworkOperationSnapshot;

public final class GetNetworkManagerConfig implements EndpointRequestType<EmptyObject, GetNetworkManagerConfig.Response> {

	@Override
	public String getMethod() {
		return "getNetworkManagerConfig";
	}

	@Override
	public JsonSerializer<EmptyObject> getRequestSerializer() {
		return EmptyObject.serializer();
	}

	@Override
	public JsonSerializer<Response> getResponseSerializer() {
		return Response.serializer();
	}

	public record ObservedInterface(String deviceState, boolean carrier, List<String> addresses, String gateway,
			List<String> dns, String activeProfile) {
		public ObservedInterface {
			addresses = List.copyOf(addresses);
			dns = List.copyOf(dns);
		}

		public static JsonSerializer<ObservedInterface> serializer() {
			var strings = JsonSerializerUtil.stringSerializer().toListSerializer();
			return JsonSerializerUtil.jsonObjectSerializer(ObservedInterface.class,
					json -> new ObservedInterface(json.getString("deviceState"), json.getBoolean("carrier"),
							json.getList("addresses", JsonSerializerUtil.stringSerializer()),
							json.getOptionalString("gateway").orElse(null),
							json.getList("dns", JsonSerializerUtil.stringSerializer()),
							json.getOptionalString("activeProfile").orElse(null)),
					obj -> JsonUtils.buildJsonObject().addProperty("deviceState", obj.deviceState())
							.addProperty("carrier", obj.carrier()).add("addresses", strings.serialize(obj.addresses()))
							.addPropertyIfNotNull("gateway", obj.gateway()).add("dns", strings.serialize(obj.dns()))
							.addPropertyIfNotNull("activeProfile", obj.activeProfile()).build());
		}
	}

	public record InterfaceEntry(String interfaceName, NetworkInterfaceConfig desired, ObservedInterface observed) {
		public static JsonSerializer<InterfaceEntry> serializer() {
			return JsonSerializerUtil.jsonObjectSerializer(InterfaceEntry.class,
					json -> new InterfaceEntry(json.getString("interfaceName"),
							json.getObject("desired", NetworkInterfaceConfig.serializer()),
							json.getObject("observed", ObservedInterface.serializer())),
					obj -> JsonUtils.buildJsonObject().addProperty("interfaceName", obj.interfaceName())
							.add("desired", NetworkInterfaceConfig.serializer().serialize(obj.desired()))
							.add("observed", ObservedInterface.serializer().serialize(obj.observed())).build());
		}
	}

	public record Response(long revision, List<InterfaceEntry> interfaces, NetworkOperationSnapshot activeOperation) {
		public Response {
			interfaces = List.copyOf(interfaces);
		}

		public static JsonSerializer<Response> serializer() {
			return JsonSerializerUtil.jsonObjectSerializer(Response.class,
					json -> new Response(json.getLong("revision"), json.getList("interfaces", InterfaceEntry.serializer()),
							json.getObjectOrNull("activeOperation", NetworkOperationSnapshot.serializer())),
					obj -> JsonUtils.buildJsonObject().addProperty("revision", obj.revision())
							.add("interfaces", InterfaceEntry.serializer().toListSerializer().serialize(obj.interfaces()))
							.addIfNotNull("activeOperation", obj.activeOperation() == null ? null
									: NetworkOperationSnapshot.serializer().serialize(obj.activeOperation()).getAsJsonObject())
							.build());
		}
	}
}
