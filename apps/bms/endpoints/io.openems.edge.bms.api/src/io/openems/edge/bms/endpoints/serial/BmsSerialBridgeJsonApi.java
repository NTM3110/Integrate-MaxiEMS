package io.openems.edge.bms.endpoints.serial;

import io.openems.edge.bms.service.serial.model.BmsSerialBridgeModels.SerialBridge;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.serial.contracts.BmsSerialBridgeService;

/** Protected API for the Serial Configuration page. */
@Component(name = "io.openems.edge.bms.api.serial.BmsSerialBridgeJsonApi", property = "entry=edge.websocket.root")
public class BmsSerialBridgeJsonApi implements JsonApi {

	public static final String LIST = "bms.tools.serial.list";
	public static final String UPDATE = "bms.tools.serial.update";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsSerialBridgeService serialBridges;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(LIST, call -> {
			this.authorize(call.getRequest().getParams());
			var bridges = new JsonArray();
			for (var bridge : this.serialBridges.list()) bridges.add(json(bridge));
			var response = new JsonObject();
			response.add("bridges", bridges);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(UPDATE, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params);
			var bridge = this.serialBridges.update(required(params, "id"), optional(params, "alias"), requiredInt(params, "baudRate"),
					requiredInt(params, "databits"), serialEnum(required(params, "parity"), "PARITY_"),
					serialEnum(required(params, "stopbits"), "STOPBITS_"), optionalInt(params, "responseTimeoutMs"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), json(bridge));
		});
	}

	private void authorize(JsonObject params) {
		if (this.authentication.authenticateToken(required(params, "token")).isEmpty()) throw new SecurityException("BMS session is invalid or expired");
	}

	private static JsonObject json(SerialBridge value) {
		var json = new JsonObject();
		json.addProperty("id", value.id());
		json.addProperty("alias", value.alias());
		json.addProperty("enabled", value.enabled());
		json.addProperty("portName", value.portName());
		json.addProperty("baudRate", value.baudRate());
		json.addProperty("databits", value.databits());
		json.addProperty("parity", value.parity());
		json.addProperty("stopbits", value.stopbits());
		json.addProperty("batchAcquisition", value.batchAcquisition());
		json.addProperty("responseTimeoutMs", value.responseTimeoutMs());
		return json;
	}

	private static String serialEnum(String value, String prefix) {
		return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
	}

	private static String required(JsonObject params, String property) {
		var value = optional(params, property);
		if (value.isBlank()) throw new IllegalArgumentException("Missing " + property);
		return value;
	}

	private static String optional(JsonObject params, String property) {
		return !params.has(property) || params.get(property).isJsonNull() ? "" : params.get(property).getAsString();
	}

	private static int requiredInt(JsonObject params, String property) {
		if (!params.has(property) || params.get(property).isJsonNull()) throw new IllegalArgumentException("Missing " + property);
		return params.get(property).getAsInt();
	}

	static Integer optionalInt(JsonObject params, String property) {
		if (!params.has(property) || params.get(property).isJsonNull()) return null;
		var value = params.get(property);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(property + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			throw new IllegalArgumentException(property + " must be an integer", e);
		}
	}
}
