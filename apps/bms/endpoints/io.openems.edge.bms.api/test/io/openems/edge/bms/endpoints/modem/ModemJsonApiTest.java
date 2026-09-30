package io.openems.edge.bms.endpoints.modem;

import io.openems.edge.bms.endpoints.modem.ModemJsonApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.modem.manager.Modem4GManager;

public class ModemJsonApiTest {

	@Test
	public void statusUsesViewerTokenAndConfigRequiresAdmin() throws Exception {
		var viewer = api(PlatformRole.BMS_VIEWER);
		var status = invoke(viewer, ModemJsonApi.STATUS, token());
		assertEquals("CONNECTED", status.get("status").getAsString());
		assertEquals(75, status.get("signalStrength").getAsInt());

		var denied = invokeResponse(viewer, ModemJsonApi.CONFIG, token());
		assertEquals("Administrator permission is required",
				denied.getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	public void updateAuthenticatesBeforeParsingAndForwardsApn() throws Exception {
		var capture = new String[3];
		var api = api(PlatformRole.PLATFORM_ADMIN, capture);
		var unauthenticated = new JsonObject();
		unauthenticated.addProperty("apn", 42);
		var denied = invokeResponse(api, ModemJsonApi.UPDATE, unauthenticated);
		assertEquals("Missing token", denied.getAsJsonObject("error").get("message").getAsString());

		var params = token();
		params.addProperty("apn", "v-internet");
		params.addProperty("username", "carrier-user");
		params.addProperty("password", "carrier-password");
		assertTrue(invoke(api, ModemJsonApi.UPDATE, params).get("success").getAsBoolean());
		assertEquals("v-internet", capture[0]);
		assertEquals("carrier-user", capture[1]);
		assertEquals("carrier-password", capture[2]);
	}

	@Test
	public void configReportsPasswordPresenceWithoutReturningPassword() throws Exception {
		var config = invoke(api(PlatformRole.PLATFORM_ADMIN), ModemJsonApi.CONFIG, token());
		assertEquals("v-internet", config.get("apn").getAsString());
		assertFalse(config.has("password"));
		assertFalse(config.get("passwordConfigured").getAsBoolean());
	}

	private static ModemJsonApi api(PlatformRole role, String... capture) throws Exception {
		var api = new ModemJsonApi();
		set(api, "authentication", new Authentication(role));
		var modem = (Modem4GManager) Proxy.newProxyInstance(Modem4GManager.class.getClassLoader(),
				new Class<?>[] { Modem4GManager.class }, (proxy, method, args) -> switch (method.getName()) {
				case "getModemStatus" -> new Modem4GManager.Status("CONNECTED", 75, -73, "Viettel", "10.1.2.3",
						"LTE", "EC200", "123456789012345", "");
				case "getModemConfig" -> new Modem4GManager.Settings("v-internet", "", false);
				case "setModemConfig" -> {
					if (capture.length == 3) {
						capture[0] = (String) args[0];
						capture[1] = (String) args[1];
						capture[2] = (String) args[2];
					}
					yield null;
				}
				default -> null;
				});
		set(api, "modem", modem);
		return api;
	}

	private static JsonObject token() {
		var params = new JsonObject();
		params.addProperty("token", "valid-token");
		return params;
	}

	private static JsonObject invoke(ModemJsonApi api, String method, JsonObject params) throws Exception {
		return invokeResponse(api, method, params).getAsJsonObject("result");
	}

	private static JsonObject invokeResponse(ModemJsonApi api, String method, JsonObject params) throws Exception {
		var builder = new JsonApiBuilder();
		api.buildJsonApiRoutes(builder);
		var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest(method, params));
		builder.handle(call);
		return call.getResponse().toJsonObject();
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class Authentication implements PlatformAuthenticationService {
		private final AuthenticatedPrincipal principal;

		private Authentication(PlatformRole role) {
			this.principal = new AuthenticatedPrincipal(UUID.randomUUID(), "user", "User", Set.of(role));
		}

		@Override
		public Optional<Login> authenticate(String username, char[] password) {
			return Optional.empty();
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public Optional<AuthenticatedPrincipal> authenticateToken(String token) {
			return "valid-token".equals(token) ? Optional.of(this.principal) : Optional.empty();
		}

		@Override public boolean logout(String token) { return false; }
		@Override public boolean changePassword(AuthenticatedPrincipal principal, char[] oldPassword, char[] newPassword) { return false; }
		@Override public Optional<AuthenticatedPrincipal> updateName(AuthenticatedPrincipal principal, String name) { return Optional.empty(); }
	}
}
