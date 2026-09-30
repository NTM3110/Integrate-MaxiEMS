package io.openems.edge.bms.endpoints.network;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.network.model.BmsNetworkModels.NetworkPort;
import io.openems.edge.bms.service.network.model.BmsNetworkModels.NetworkUpdate;
import io.openems.edge.bms.service.network.contracts.BmsNetworkService;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Protected API for reading and updating the BMS appliance's configured WAN ports. */
@Component(name = "io.openems.edge.bms.api.network.BmsNetworkJsonApi", property = "entry=edge.websocket.root")
public class BmsNetworkJsonApi implements JsonApi {

	public static final String LIST = "bms.tools.network.list";
	public static final String UPDATE = "bms.tools.network.update";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsNetworkService network;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(LIST, call -> {
			this.authorize(call.getRequest().getParams(), false);
			var ports = new JsonArray();
			for (var port : this.network.list()) ports.add(json(port));
			var response = new JsonObject();
			response.add("networks", ports);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(UPDATE, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, true);
			var port = this.network.update(required(params, "id"), new NetworkUpdate(required(params, "mode"),
					optional(params, "ipAddress"), optional(params, "subnetMask"), optional(params, "gateway"), optional(params, "dns")));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), json(port));
		});
	}

	private void authorize(JsonObject params, boolean requireAdmin) {
		var principal = this.authentication.authenticateToken(required(params, "token"))
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
		if (requireAdmin && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("Administrator permission is required");
		}
	}

	private static JsonObject json(NetworkPort port) {
		var json = new JsonObject();
		json.addProperty("id", port.id());
		json.addProperty("name", port.name());
		json.addProperty("interfaceName", port.interfaceName());
		json.addProperty("available", port.available());
		json.addProperty("connected", port.connected());
		json.addProperty("status", port.status());
		addNullable(json, "error", port.error());
		addNullable(json, "ipAddress", port.ipAddress());
		addNullable(json, "subnetMask", port.subnetMask());
		addNullable(json, "gateway", port.gateway());
		addNullable(json, "dns", port.dns());
		json.addProperty("mode", port.mode());
		return json;
	}

	private static void addNullable(JsonObject json, String name, String value) {
		if (value == null) json.add(name, com.google.gson.JsonNull.INSTANCE);
		else json.addProperty(name, value);
	}

	private static String required(JsonObject params, String property) {
		var value = optional(params, property);
		if (value.isBlank()) throw new IllegalArgumentException("Missing " + property);
		return value;
	}

	private static String optional(JsonObject params, String property) {
		return !params.has(property) || params.get(property).isJsonNull() ? "" : params.get(property).getAsString();
	}
}
