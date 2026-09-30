package io.openems.edge.bms.endpoints.discharge;

import io.openems.edge.bms.service.discharge.contracts.DischargeCalibrationOperations;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;


import java.time.Instant;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.discharge.contracts.DischargeQueryService;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** JSON-RPC facade for the Svelte discharge-calibration page. */
@Component(name = "io.openems.edge.bms.platform.discharge.DischargeJsonApi", property = "entry=edge.websocket.root")
public class DischargeJsonApi implements JsonApi {

	@Reference
	private PlatformAuthenticationService authentication;
	@Reference
	private DischargeCalibrationOperations service;
	@Reference
	private DischargeQueryService queries;

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest("bms.tools.discharge.create", call -> {
			var params = call.getRequest().getParams();
			this.authorizeMutation(params);
			this.service.create(integer(params, "stringIndex"), Instant.parse(string(params, "startTime")),
					bool(params, "automaticMode", true), number(params, "automaticStartCurrentA", 6),
					optional(params, "eventType", "BASELINE_CALIBRATION"), nullableNumber(params, "ratedCapacityAh"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest("bms.tools.discharge.list", call -> {
			this.authenticate(call.getRequest().getParams());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.queries.events());
		});
		builder.handleRequest("bms.tools.discharge.samples", call -> {
			var params = call.getRequest().getParams();
			this.authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.queries.samples(integer(params, "eventId")));
		});
		builder.handleRequest("bms.tools.discharge.getConfig", call -> {
			this.authenticate(call.getRequest().getParams());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.queries.config());
		});
		builder.handleRequest("bms.tools.discharge.saveConfig", call -> {
			var params = call.getRequest().getParams();
			this.authorizeMutation(params);
			this.queries.saveConfig(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.queries.config());
		});
		builder.handleRequest("bms.tools.discharge.stop", call -> {
			var params = call.getRequest().getParams();
			this.authorizeMutation(params);
			this.service.stop(integer(params, "id"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest("bms.tools.discharge.delete", call -> {
			var params = call.getRequest().getParams();
			this.authorizeMutation(params);
			this.service.delete(integer(params, "id"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest("bms.tools.discharge.activateBaseline", call -> {
			var params = call.getRequest().getParams();
			this.authorizeMutation(params);
			this.service.activateBaseline(integer(params, "stringIndex"), integer(params, "id"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
		builder.handleRequest("bms.tools.discharge.curve", call -> {
			var params = call.getRequest().getParams();
			this.authenticate(params);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), this.queries.curve(integer(params, "stringIndex")));
		});
		builder.handleRequest("bms.tools.discharge.promoteCurve", call -> {
			var params = call.getRequest().getParams();
			this.authorizeMutation(params);
			this.queries.promoteCurve(integer(params, "stringIndex"), integer(params, "sourceEventId"));
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId());
		});
	}

	private AuthenticatedPrincipal authenticate(JsonObject params) {
		if (!params.has("token") || !params.get("token").isJsonPrimitive()
				|| !params.getAsJsonPrimitive("token").isString() || params.get("token").getAsString().isBlank()) {
			throw new SecurityException("BMS session is invalid or expired");
		}
		return this.authentication.authenticateToken(params.get("token").getAsString())
				.orElseThrow(() -> new SecurityException("BMS session is invalid or expired"));
	}

	private void authorizeMutation(JsonObject params) {
		if (!this.authenticate(params).hasRole(PlatformRole.PLATFORM_ADMIN)) {
			throw new SecurityException("Administrator permission is required");
		}
	}

	private static void add(JsonObject json, String key, Number value) {
		if (value == null) json.add(key, JsonNull.INSTANCE); else json.addProperty(key, value);
	}

	private static void add(JsonObject json, String key, String value) {
		if (value == null) json.add(key, JsonNull.INSTANCE); else json.addProperty(key, value);
	}

	private static String string(JsonObject params, String key) {
		if (!params.has(key) || !params.get(key).isJsonPrimitive()
				|| !params.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Missing " + key);
		return params.get(key).getAsString();
	}

	private static String optional(JsonObject params, String key, String defaultValue) {
		return !params.has(key) || params.get(key).isJsonNull() ? defaultValue : params.get(key).getAsString();
	}

	private static int integer(JsonObject params, String key) {
		if (!params.has(key)) throw new IllegalArgumentException("Missing " + key);
		return params.get(key).getAsInt();
	}

	private static int positiveInteger(JsonObject params, String key) {
		var value = integer(params, key);
		if (value <= 0) throw new IllegalArgumentException(key + " must be greater than zero");
		return value;
	}

	private static boolean bool(JsonObject params, String key, boolean defaultValue) {
		return !params.has(key) ? defaultValue : params.get(key).getAsBoolean();
	}

	private static double number(JsonObject params, String key, double defaultValue) {
		var value = !params.has(key) ? defaultValue : params.get(key).getAsDouble();
		if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
		return value;
	}

	private static Double nullableNumber(JsonObject params, String key) {
		if (!params.has(key) || params.get(key).isJsonNull()) return null;
		var value = params.get(key).getAsDouble();
		if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
		return value;
	}

	private static double positive(double value, String label) {
		if (value <= 0) throw new IllegalArgumentException(label + " must be greater than zero");
		return value;
	}

	private static double nonNegative(double value, String label) {
		if (value < 0) throw new IllegalArgumentException(label + " must not be negative");
		return value;
	}
}
