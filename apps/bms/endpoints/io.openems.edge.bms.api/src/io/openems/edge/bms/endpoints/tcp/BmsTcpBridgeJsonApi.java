package io.openems.edge.bms.endpoints.tcp;

import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.tcp.contracts.BmsTcpBridgeService;
import io.openems.edge.bms.service.tcp.model.BmsTcpBridgeModels.TcpBridge;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Authenticated BMS API for outbound Modbus/TCP bridges. */
@Component(name = "io.openems.edge.bms.api.tcp.BmsTcpBridgeJsonApi", property = "entry=edge.websocket.root")
public class BmsTcpBridgeJsonApi implements JsonApi {

	public static final String LIST = "bms.tools.tcp.list";
	public static final String CREATE = "bms.tools.tcp.create";
	public static final String UPDATE = "bms.tools.tcp.update";
	public static final String DELETE = "bms.tools.tcp.delete";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsTcpBridgeService tcpBridges;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(LIST, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, false);
			var bridges = new JsonArray();
			for (var bridge : this.tcpBridges.list()) {
				bridges.add(json(bridge));
			}
			var response = new JsonObject();
			response.add("bridges", bridges);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(CREATE, call -> success(call.getRequest().getId(), create(call.getRequest().getParams())));
		builder.handleRequest(UPDATE, call -> success(call.getRequest().getId(), update(call.getRequest().getParams())));
		builder.handleRequest(DELETE, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, true);
			this.tcpBridges.delete(required(params, "id"));
			var result = new JsonObject();
			result.addProperty("deleted", true);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
	}

	TcpBridge create(JsonObject params) throws Exception {
		this.authorize(params, true);
		return this.tcpBridges.create(required(params, "id"), optional(params, "alias"),
				optionalBoolean(params, "enabled", true), required(params, "ip"), requiredInt(params, "port"));
	}

	TcpBridge update(JsonObject params) throws Exception {
		this.authorize(params, true);
		return this.tcpBridges.update(required(params, "id"), optional(params, "alias"),
				optionalBoolean(params, "enabled", true), required(params, "ip"), requiredInt(params, "port"));
	}

	void authorize(JsonObject params, boolean administrator) {
		if (!params.has("token") || !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString() || params.get("token").getAsString().isBlank()) {
			throw new SecurityException("BMS session is invalid or expired");
		}
		var principal = this.authentication.authenticateToken(params.get("token").getAsString())
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
		if (administrator && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("Administrator permission is required");
		}
	}

	static JsonObject json(TcpBridge value) {
		var json = new JsonObject();
		json.addProperty("id", value.id());
		json.addProperty("alias", value.alias());
		json.addProperty("enabled", value.enabled());
		json.addProperty("ip", value.ip());
		json.addProperty("port", value.port());
		return json;
	}

	private static GenericJsonrpcResponseSuccess success(UUID id, TcpBridge bridge) {
		return new GenericJsonrpcResponseSuccess(id, json(bridge));
	}

	private static String required(JsonObject params, String property) {
		var value = optional(params, property);
		if (value.isBlank()) {
			throw new IllegalArgumentException("Missing " + property);
		}
		return value;
	}

	private static String optional(JsonObject params, String property) {
		return !params.has(property) || params.get(property).isJsonNull() ? "" : params.get(property).getAsString();
	}

	private static int requiredInt(JsonObject params, String property) {
		if (!params.has(property) || params.get(property).isJsonNull()) {
			throw new IllegalArgumentException("Missing " + property);
		}
		return params.get(property).getAsInt();
	}

	private static boolean optionalBoolean(JsonObject params, String property, boolean fallback) {
		return !params.has(property) || params.get(property).isJsonNull() ? fallback : params.get(property).getAsBoolean();
	}
}
