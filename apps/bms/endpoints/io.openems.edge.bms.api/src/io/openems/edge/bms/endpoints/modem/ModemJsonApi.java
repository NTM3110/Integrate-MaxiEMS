package io.openems.edge.bms.endpoints.modem;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.modem.manager.Modem4GManager;

/** Platform-token authenticated facade for the local 4G modem component. */
@Component(name = "io.openems.edge.bms.platform.modem.ModemJsonApi", property = "entry=edge.websocket.root")
public class ModemJsonApi implements JsonApi {

	public static final String STATUS = "bms.tools.modem.status";
	public static final String CONFIG = "bms.tools.modem.config";
	public static final String UPDATE = "bms.tools.modem.update";

	@Reference(target = "(id=modem4g0)")
	private Modem4GManager modem;
	@Reference
	private PlatformAuthenticationService authentication;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(STATUS, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, false);
			var status = this.modem.getModemStatus();
			var response = new JsonObject();
			response.addProperty("status", status.status());
			response.addProperty("signalStrength", status.signalStrength());
			response.addProperty("signalDbm", status.signalDbm());
			response.addProperty("operator", status.operator());
			response.addProperty("ipAddress", status.ipAddress());
			response.addProperty("connectionType", status.connectionType());
			response.addProperty("modemModel", status.modemModel());
			response.addProperty("imei", status.imei());
			response.addProperty("lastError", status.lastError());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(CONFIG, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, true);
			var config = this.modem.getModemConfig();
			var response = new JsonObject();
			response.addProperty("enabled", true);
			response.addProperty("apn", config.apn());
			response.addProperty("username", config.username());
			response.addProperty("passwordConfigured", config.passwordConfigured());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(UPDATE, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, true);
			var apn = required(params, "apn");
			var username = optional(params, "username");
			var password = optional(params, "password");
			this.modem.setModemConfig(apn, username, password);
			var response = new JsonObject();
			response.addProperty("success", true);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
	}

	private AuthenticatedPrincipal authorize(JsonObject params, boolean requireAdmin) {
		var principal = this.authentication.authenticateToken(required(params, "token"))
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
		if (requireAdmin && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("Administrator permission is required");
		}
		if (!requireAdmin && !principal.hasRole(PlatformRole.BMS_VIEWER)
				&& !principal.hasRole(PlatformRole.BMS_OPERATOR)
				&& !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("BMS viewer permission is required");
		}
		return principal;
	}

	private static String required(JsonObject params, String property) {
		var value = optional(params, property);
		if (value.isBlank()) {
			throw new IllegalArgumentException("Missing " + property);
		}
		return value;
	}

	private static String optional(JsonObject params, String property) {
		if (!params.has(property) || params.get(property).isJsonNull()) {
			return "";
		}
		if (!params.get(property).isJsonPrimitive() || !params.getAsJsonPrimitive(property).isString()) {
			throw new IllegalArgumentException(property + " must be a string");
		}
		return params.get(property).getAsString();
	}
}
