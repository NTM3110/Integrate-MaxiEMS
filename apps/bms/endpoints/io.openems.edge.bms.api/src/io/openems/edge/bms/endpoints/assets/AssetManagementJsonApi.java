package io.openems.edge.bms.endpoints.assets;

import java.time.Instant;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.assets.contracts.AssetManagementService;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Authenticated Asset Management profile API. */
@Component(name = "io.openems.edge.bms.platform.assets.AssetManagementJsonApi", property = "entry=edge.websocket.root")
public class AssetManagementJsonApi implements JsonApi {
	public static final String GET_SITE = "bms.assets.site.get";
	public static final String GET_STRING = "bms.assets.string.get";
	public static final String SAVE = "bms.assets.save";
	public static final String LIST_HISTORY = "bms.assets.sohHistory.list";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private AssetManagementService assets;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET_SITE, call -> {
			this.authorize(call.getRequest().getParams(), PlatformRole.BMS_VIEWER);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.assets.getSite());
		});
		builder.handleRequest(GET_STRING, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, PlatformRole.BMS_VIEWER);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					this.assets.getString(requiredInt(params, "stringIndex")));
		});
		builder.handleRequest(SAVE, call -> {
			var params = call.getRequest().getParams();
			var principal = this.authorize(params, PlatformRole.PLATFORM_ADMIN);
			var index = params.has("stringIndex") && !params.get("stringIndex").isJsonNull()
					? requiredInt(params, "stringIndex") : null;
			var device = params.has("stringProfile") ? requiredObject(params, "stringProfile") : null;
			if (index != null && device == null) throw new IllegalArgumentException("stringProfile is required");
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.assets.save(
					requiredObject(params, "siteProfile"), index, device, principal.id()));
		});
		builder.handleRequest(LIST_HISTORY, call -> {
			var params = call.getRequest().getParams();
			this.authorize(params, PlatformRole.BMS_VIEWER);
			var limit = params.has("limit") && !params.get("limit").isJsonNull()
					? requiredInt(params, "limit") : 100;
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(),
					this.assets.listSohHistory(requiredInt(params, "stringIndex"), optionalInstant(params, "from"),
							optionalInstant(params, "to"), limit));
		});
	}

	private AuthenticatedPrincipal authorize(JsonObject params, PlatformRole role) {
		var token = params.get("token");
		if (token == null || !token.isJsonPrimitive() || !token.getAsJsonPrimitive().isString()
				|| token.getAsString().isBlank()) throw new IllegalArgumentException("Missing token");
		var principal = this.authentication.authenticateToken(token.getAsString())
				.orElseThrow(() -> new SecurityException("Session is invalid or expired"));
		if (!principal.hasRole(role)) throw new SecurityException("Insufficient role for Asset Management");
		return principal;
	}

	private static JsonObject requiredObject(JsonObject params, String key) {
		JsonElement value = params.get(key);
		if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
		return value.getAsJsonObject();
	}

	private static int requiredInt(JsonObject params, String key) {
		JsonElement value = params.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
			throw new IllegalArgumentException(key + " must be an integer");
		try {
			return Integer.parseInt(value.getAsString());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
	}

	private static Instant optionalInstant(JsonObject params, String key) {
		var value = params.get(key);
		if (value == null || value.isJsonNull()) return null;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
			throw new IllegalArgumentException(key + " must be an ISO timestamp");
		try {
			return Instant.parse(value.getAsString());
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(key + " must be an ISO timestamp");
		}
	}
}
