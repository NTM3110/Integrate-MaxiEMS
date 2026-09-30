package io.openems.edge.bms.endpoints.auth;

import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** WebSocket JSON-RPC facade for the Svelte authentication service. */
@Component(name = "io.openems.edge.bms.platform.auth.PlatformAuthenticationJsonApi", property = "entry=edge.websocket.root")
public class PlatformAuthenticationJsonApi implements JsonApi {

	public static final String AUTHENTICATE = "platformAuthenticate";
	public static final String AUTHENTICATE_TOKEN = "platformAuthenticateWithToken";
	public static final String LOGOUT = "platformLogout";
	public static final String CHANGE_PASSWORD = "platformChangePassword";
	public static final String UPDATE_ACCOUNT_NAME = "platformUpdateAccountName";
	public static final String READY = "platformReady";

	@Reference
	private PlatformAuthenticationService authentication;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(READY, call -> {
			var response = new JsonObject();
			response.addProperty("ready", this.authentication.isAvailable());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response);
		});
		builder.handleRequest(AUTHENTICATE, call -> {
			if (!this.authentication.isAvailable()) {
				throw new IllegalStateException("Platform authentication database is unavailable. Check the PostgreSQL connection settings.");
			}
			var params = call.getRequest().getParams();
			var login = this.authentication.authenticate(required(params, "username"), required(params, "password").toCharArray());
			if (login.isEmpty()) throw new SecurityException("Invalid username or password");
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), loginJson(login.get()));
		});
		builder.handleRequest(AUTHENTICATE_TOKEN, call -> {
			var principal = this.authentication.authenticateToken(required(call.getRequest().getParams(), "token"));
			if (principal.isEmpty()) throw new SecurityException("Session is invalid or expired");
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), principalJson(principal.get()));
		});
		builder.handleRequest(LOGOUT, call -> {
			this.authentication.logout(required(call.getRequest().getParams(), "token"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest(CHANGE_PASSWORD, call -> {
			var params = call.getRequest().getParams();
			var principal = this.authentication.authenticateToken(required(params, "token"));
			if (principal.isEmpty() || !this.authentication.changePassword(principal.get(), required(params, "oldPassword").toCharArray(), required(params, "newPassword").toCharArray())) {
				throw new SecurityException("Password change was rejected");
			}
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest(UPDATE_ACCOUNT_NAME, call -> {
			var params = call.getRequest().getParams();
			var principal = this.authentication.authenticateToken(required(params, "token"))
					.orElseThrow(() -> new SecurityException("Session is invalid or expired"));
			var updated = this.authentication.updateName(principal, required(params, "name"))
					.orElseThrow(() -> new IllegalArgumentException("Account name must be between 1 and 160 characters"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), principalJson(updated));
		});
	}

	private static String required(JsonObject params, String field) {
		if (!params.has(field) || params.get(field).isJsonNull() || params.get(field).getAsString().isBlank()) throw new IllegalArgumentException("Missing " + field);
		return params.get(field).getAsString();
	}

	private static JsonObject loginJson(PlatformAuthenticationService.Login login) {
		var json = principalJson(login.principal()); json.addProperty("token", login.token()); json.addProperty("expiresAt", login.expiresAt().toString()); return json;
	}

	private static JsonObject principalJson(AuthenticatedPrincipal principal) {
		var json = new JsonObject(); json.addProperty("userId", principal.id().toString()); json.addProperty("username", principal.username());
		json.addProperty("name", principal.name());
		var roles = new JsonArray(); principal.roles().forEach(role -> roles.add(role.name())); json.add("roles", roles); return json;
	}
}
