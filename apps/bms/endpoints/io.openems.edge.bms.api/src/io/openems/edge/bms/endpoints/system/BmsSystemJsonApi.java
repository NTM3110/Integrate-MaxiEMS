package io.openems.edge.bms.endpoints.system;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.system.contracts.BmsSystemService;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Administrator-only system actions for the BMS Maintenance page. */
@Component(name = "io.openems.edge.bms.api.system.BmsSystemJsonApi", property = "entry=edge.websocket.root")
public class BmsSystemJsonApi implements JsonApi {

	public static final String REBOOT = "bms.tools.system.reboot";

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsSystemService system;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(REBOOT, call -> {
			this.authorizeAdministrator(call.getRequest().getParams());
			this.system.reboot();
			var result = new JsonObject();
			result.addProperty("accepted", true);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
	}

	private void authorizeAdministrator(JsonObject params) {
		if (!params.has("token") || params.get("token").isJsonNull()) throw new SecurityException("BMS session is invalid or expired");
		var principal = this.authentication.authenticateToken(params.get("token").getAsString())
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
		if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) throw new SecurityException("Administrator permission is required");
	}
}
