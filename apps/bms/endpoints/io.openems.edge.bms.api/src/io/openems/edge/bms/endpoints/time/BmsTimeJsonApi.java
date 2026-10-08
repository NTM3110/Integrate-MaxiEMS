package io.openems.edge.bms.endpoints.time;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import com.google.gson.JsonObject;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.time.contracts.BmsTimeService;
import io.openems.edge.bms.service.time.model.BmsTimeStatus;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

@Component(name = "io.openems.edge.bms.api.time.BmsTimeJsonApi", property = "entry=edge.websocket.root")
public class BmsTimeJsonApi implements JsonApi {
	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private BmsTimeService time;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest("bms.tools.time.status", call -> {
			this.authorize(call.getRequest().getParams(), false);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), json(this.time.status()));
		});
		builder.handleRequest("bms.tools.time.configure", call -> new GenericJsonrpcResponseSuccess(
				call.getRequest().getId(), this.configure(call.getRequest().getParams())));
	}

	JsonObject configure(JsonObject params) throws OpenemsException {
		this.authorize(params, true);
		if (!params.has("serverIp") || !params.get("serverIp").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("serverIp").isString()) {
			throw new IllegalArgumentException("serverIp must be a string");
		}
		return json(this.time.configure(params.get("serverIp").getAsString()));
	}

	void authorize(JsonObject params, boolean administrator) {
		if (!params.has("token") || !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString()) {
			throw new SecurityException("BMS session is invalid or expired");
		}
		var principal = this.authentication.authenticateToken(params.get("token").getAsString())
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
		if (administrator && !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("Administrator permission is required");
		}
	}

	static JsonObject json(BmsTimeStatus status) {
		var json = new JsonObject();
		json.addProperty("configuredServer", status.configuredServer());
		json.addProperty("serverName", status.serverName());
		json.addProperty("serverAddress", status.serverAddress());
		json.addProperty("synchronized", status.synchronizedClock());
		json.addProperty("ntpServiceActive", status.ntpServiceActive());
		json.addProperty("timezone", status.timezone());
		json.addProperty("serviceState", status.serviceState());
		json.addProperty("currentTime", status.currentTime());
		json.addProperty("serverReachable", status.serverReachable());
		json.addProperty("reachabilityError", status.reachabilityError());
		json.addProperty("packetCount", status.packetCount());
		json.addProperty("pollInterval", status.pollInterval());
		json.addProperty("error", status.error());
		return json;
	}
}
