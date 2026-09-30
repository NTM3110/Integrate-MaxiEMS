package io.openems.edge.bms.endpoints.email;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;

import java.util.ArrayList;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

/** Administrator-only, secret-safe email-alert JSON-RPC endpoints. */
@Component(name = "io.openems.edge.bms.platform.email.EmailAlertJsonApi", property = "entry=edge.websocket.root")
public class EmailAlertJsonApi implements JsonApi {
	public static final String GET = "bms.email.get", SAVE = "bms.email.save", TEST = "bms.email.test", STATUS = "bms.email.status";
	@Reference private PlatformAuthenticationService authentication;
	@Reference private EmailAlertService email;

	@Override public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(GET, call -> { authorize(call.getRequest().getParams()); return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response(this.email.getSettings())); });
		builder.handleRequest(SAVE, call -> { var p = call.getRequest().getParams(); authorize(p); return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), response(this.email.save(settings(p), optionalString(p, "password")))); });
		builder.handleRequest(TEST, call -> { var p = call.getRequest().getParams(); authorize(p); var result = new JsonObject(); result.addProperty("message", this.email.sendTest(optionalString(p, "subject", "MaxiEMS email alert test"), optionalString(p, "body", "This is a MaxiEMS email-alert configuration test."))); return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result); });
		builder.handleRequest(STATUS, call -> { authorize(call.getRequest().getParams()); var result = new JsonObject(); var events = new JsonArray(); for (var status : this.email.getDeliveryStatus()) { var event = new JsonObject(); event.addProperty("eventKey", status.eventKey()); event.addProperty("active", status.active()); event.addProperty("lastSentAt", status.lastSentAt()); event.addProperty("lastDeliveryStatus", status.lastDeliveryStatus()); event.addProperty("updatedAt", status.updatedAt()); events.add(event); } result.add("events", events); return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result); });
	}
	private void authorize(JsonObject p) { if (!p.has("token") || !p.get("token").isJsonPrimitive() || !p.getAsJsonPrimitive("token").isString() || p.get("token").getAsString().isBlank()) throw new SecurityException("Session is invalid or expired"); var principal = this.authentication.authenticateToken(p.get("token").getAsString()).orElseThrow(() -> new SecurityException("Session is invalid or expired")); if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) throw new SecurityException("Administrator permission is required"); }
	private static EmailAlertService.Settings settings(JsonObject p) { return new EmailAlertService.Settings(requiredBoolean(p, "enabled"), requiredString(p, "host"), requiredInt(p, "port"), requiredString(p, "security"), requiredString(p, "username"), false, requiredString(p, "senderAddress"), requiredString(p, "senderName"), strings(p, "recipients"), strings(p, "categories"), requiredBoolean(p, "sendRecovery"), requiredInt(p, "reminderMinutes")); }
	private static JsonObject response(EmailAlertService.Settings settings) { var out = new JsonObject(); out.addProperty("enabled", settings.enabled()); out.addProperty("host", settings.host()); out.addProperty("port", settings.port()); out.addProperty("security", settings.security()); out.addProperty("username", settings.username()); out.addProperty("passwordConfigured", settings.passwordConfigured()); out.addProperty("senderAddress", settings.senderAddress()); out.addProperty("senderName", settings.senderName()); out.add("recipients", array(settings.recipients())); out.add("categories", array(settings.categories())); out.addProperty("sendRecovery", settings.sendRecovery()); out.addProperty("reminderMinutes", settings.reminderMinutes()); return out; }
	private static JsonArray array(Iterable<String> values) { var out = new JsonArray(); values.forEach(out::add); return out; }
	private static String requiredString(JsonObject p, String key) { var value = optionalString(p, key); if (value == null) throw new IllegalArgumentException(key + " must be a string"); return value; }
	private static String optionalString(JsonObject p, String key) { return p.has(key) && p.get(key).isJsonPrimitive() && p.getAsJsonPrimitive(key).isString() ? p.get(key).getAsString() : null; }
	private static String optionalString(JsonObject p, String key, String fallback) { var value = optionalString(p, key); return value == null ? fallback : value; }
	private static int requiredInt(JsonObject p, String key) { if (!p.has(key) || !p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException(key + " must be an integer"); var value = p.get(key).getAsDouble(); if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " must be an integer"); return (int) value; }
	private static boolean requiredBoolean(JsonObject p, String key) { if (!p.has(key) || !p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException(key + " must be a boolean"); return p.get(key).getAsBoolean(); }
	private static ArrayList<String> strings(JsonObject p, String key) { if (!p.has(key) || !p.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array"); var out = new ArrayList<String>(); for (var item : p.getAsJsonArray(key)) { if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must contain strings"); out.add(item.getAsString().trim()); } return out; }
}
