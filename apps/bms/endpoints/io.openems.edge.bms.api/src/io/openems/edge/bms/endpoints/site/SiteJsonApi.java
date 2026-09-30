package io.openems.edge.bms.endpoints.site;

import io.openems.edge.bms.service.site.contracts.SiteSettingService;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Authenticated API for the PostgreSQL-backed site display name. */
@Component(name = "io.openems.edge.bms.platform.site.SiteJsonApi", property = "entry=edge.websocket.root")
public class SiteJsonApi implements JsonApi {

	public static final String GET = "bms.site.get";
	public static final String SET = "bms.site.set";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private SiteSettingService settings;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET, call -> {
			this.authenticate(call.getRequest().getParams());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response(this.settings.getSiteName()));
		});
		builder.handleRequest(SET, call -> {
			var params = call.getRequest().getParams();
			var principal = this.authenticate(params);
			if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
				throw new SecurityException("Administrator permission is required");
			}
			if (!params.has("siteName") || !params.get("siteName").isJsonPrimitive()
					|| !params.getAsJsonPrimitive("siteName").isString()) {
				throw new IllegalArgumentException("Site name must be a string");
			}
			var siteName = this.settings.setSiteName(params.get("siteName").getAsString(), principal.id());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response(siteName));
		});
	}

	private AuthenticatedPrincipal authenticate(JsonObject params) {
		if (!params.has("token") || !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString() || params.get("token").getAsString().isBlank()) {
			throw new IllegalArgumentException("Missing token");
		}
		return this.authentication.authenticateToken(params.get("token").getAsString())
				.orElseThrow(() -> new SecurityException("Session is invalid or expired"));
	}

	private static JsonObject response(String siteName) {
		var response = new JsonObject();
		response.addProperty("siteName", siteName);
		return response;
	}
}
