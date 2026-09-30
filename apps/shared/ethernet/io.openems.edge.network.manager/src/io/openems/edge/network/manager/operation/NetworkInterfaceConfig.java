package io.openems.edge.network.manager.operation;

import java.util.List;
import java.util.Objects;

import io.openems.common.jsonrpc.serialization.JsonSerializer;
import io.openems.common.jsonrpc.serialization.JsonSerializerUtil;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.network.manager.Mode;

public record NetworkInterfaceConfig(String interfaceName, Mode mode, String ip, Integer prefixLength, String gateway,
		List<String> dns) {

	public NetworkInterfaceConfig {
		Objects.requireNonNull(interfaceName);
		Objects.requireNonNull(mode);
		dns = List.copyOf(dns);
	}

	public List<String> candidateAddresses() {
		return this.ip == null ? List.of() : List.of(this.ip);
	}

	public static JsonSerializer<NetworkInterfaceConfig> serializer() {
		var strings = JsonSerializerUtil.stringSerializer().toListSerializer();
		return JsonSerializerUtil.jsonObjectSerializer(NetworkInterfaceConfig.class,
				json -> new NetworkInterfaceConfig(json.getString("interfaceName"), json.getEnum("mode", Mode.class),
						json.getOptionalString("ip").orElse(null),
						json.getOptionalLong("prefixLength").map(Long::intValue).orElse(null),
						json.getOptionalString("gateway").orElse(null), json.getList("dns", JsonSerializerUtil.stringSerializer())),
				obj -> JsonUtils.buildJsonObject().addProperty("interfaceName", obj.interfaceName())
						.addProperty("mode", obj.mode()).addPropertyIfNotNull("ip", obj.ip())
						.addPropertyIfNotNull("prefixLength", obj.prefixLength())
						.addPropertyIfNotNull("gateway", obj.gateway()).add("dns", strings.serialize(obj.dns())).build());
	}
}
