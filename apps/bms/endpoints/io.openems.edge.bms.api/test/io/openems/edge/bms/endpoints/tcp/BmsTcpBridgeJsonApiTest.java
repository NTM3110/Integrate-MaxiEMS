package io.openems.edge.bms.endpoints.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.google.gson.JsonObject;

import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.tcp.contracts.BmsTcpBridgeService;
import io.openems.edge.bms.service.tcp.model.BmsTcpBridgeModels.TcpBridge;

public class BmsTcpBridgeJsonApiTest {

	@Test
	public void mutationAuthenticatesBeforeParsingAndRequiresAdministrator() throws Exception {
		var api = api();
		var params = new JsonObject();
		assertThrows(SecurityException.class, () -> api.create(params));

		params.addProperty("token", "token");
		var authentication = authentication(api);
		when(authentication.authenticateToken("token")).thenReturn(Optional.of(principal(PlatformRole.BMS_VIEWER)));
		assertThrows(SecurityException.class, () -> api.create(params));

		when(authentication.authenticateToken("token")).thenReturn(Optional.of(principal(PlatformRole.PLATFORM_ADMIN)));
		assertThrows(IllegalArgumentException.class, () -> api.create(params));
	}

	@Test
	public void responseUsesStableTcpBridgeFields() {
		var json = BmsTcpBridgeJsonApi.json(new TcpBridge("modbus3", "UPS TCP", true, "192.168.1.10", 502));
		assertEquals("modbus3", json.get("id").getAsString());
		assertEquals("UPS TCP", json.get("alias").getAsString());
		assertEquals("192.168.1.10", json.get("ip").getAsString());
		assertEquals(502, json.get("port").getAsInt());
		assertEquals(5, json.size());
	}

	private static BmsTcpBridgeJsonApi api() throws Exception {
		var api = new BmsTcpBridgeJsonApi();
		set(api, "authentication", mock(PlatformAuthenticationService.class));
		set(api, "tcpBridges", mock(BmsTcpBridgeService.class));
		return api;
	}

	private static PlatformAuthenticationService authentication(BmsTcpBridgeJsonApi api) throws Exception {
		var field = BmsTcpBridgeJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		return (PlatformAuthenticationService) field.get(api);
	}

	private static AuthenticatedPrincipal principal(PlatformRole role) {
		return new AuthenticatedPrincipal(UUID.randomUUID(), "user", "User", Set.of(role));
	}

	private static void set(Object target, String name, Object value) throws Exception {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
