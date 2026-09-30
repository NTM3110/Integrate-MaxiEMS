package io.openems.edge.bms.endpoints.keyboard;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.keyboard.contracts.BmsKeyboardService;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** API boundary for the kiosk's Sway-owned virtual keyboard controller. */
@Component(name = "io.openems.edge.bms.api.keyboard.BmsKeyboardJsonApi", property = "entry=edge.websocket.root")
public class BmsKeyboardJsonApi implements JsonApi {
	public static final String COMMAND = "bms.tools.keyboard";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsKeyboardService keyboard;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(COMMAND, call -> {
			var params = call.getRequest().getParams();
			var action = BmsKeyboardService.Action.parse(required(params, "action"));
			// Login fields need a keyboard before a session exists. Every other
			// command requires a current BMS session.
			if (action != BmsKeyboardService.Action.NORMAL || params.has("token")) authorize(params);
			this.keyboard.command(action);
			var response = new JsonObject();
			response.addProperty("action", action.wireName());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
	}

	private void authorize(JsonObject params) {
		var token = required(params, "token");
		if (this.authentication.authenticateToken(token).isEmpty()) {
			throw new SecurityException("BMS session is invalid or expired");
		}
	}

	private static String required(JsonObject params, String property) {
		if (!params.has(property) || params.get(property).isJsonNull()) throw new IllegalArgumentException("Missing " + property);
		var value = params.get(property).getAsString();
		if (value.isBlank()) throw new IllegalArgumentException("Missing " + property);
		return value;
	}
}
