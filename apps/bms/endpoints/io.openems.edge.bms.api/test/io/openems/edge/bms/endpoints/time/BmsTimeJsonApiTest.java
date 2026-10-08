package io.openems.edge.bms.endpoints.time;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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
import io.openems.edge.bms.service.time.model.BmsTimeStatus;

public class BmsTimeJsonApiTest {
	@Test
	public void statusNeedsSessionAndConfigureNeedsAdmin() throws Exception {
		var api = new BmsTimeJsonApi();
		var auth = mock(PlatformAuthenticationService.class);
		var field = BmsTimeJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		field.set(api, auth);
		var params = new JsonObject();
		assertThrows(SecurityException.class, () -> api.authorize(params, false));
		params.addProperty("token", "token");
		when(auth.authenticateToken("token")).thenReturn(Optional.empty());
		assertThrows(SecurityException.class, () -> api.authorize(params, false));
		when(auth.authenticateToken("token")).thenReturn(Optional.of(new AuthenticatedPrincipal(
				UUID.randomUUID(), "viewer", "Viewer", Set.of(PlatformRole.BMS_VIEWER))));
		api.authorize(params, false);
		assertThrows(SecurityException.class, () -> api.authorize(params, true));
		assertThrows(SecurityException.class, () -> api.configure(params)); // Auth before missing serverIp.
		when(auth.authenticateToken("token")).thenReturn(Optional.of(new AuthenticatedPrincipal(
				UUID.randomUUID(), "admin", "Admin", Set.of(PlatformRole.PLATFORM_ADMIN))));
		api.authorize(params, true);
		assertThrows(IllegalArgumentException.class, () -> api.configure(params));
	}

	@Test
	public void responsePreservesUnknownAndBooleanValues() {
		var json = BmsTimeJsonApi.json(new BmsTimeStatus(null, null, null, false, true, "Asia/Ho_Chi_Minh",
				"active", "2026-10-06T14:00:00+07:00", false, "No valid NTP response", 0L, "34min 8s", null));
		assertTrue(json.get("serverAddress").isJsonNull());
		assertFalse(json.get("synchronized").getAsBoolean());
		assertTrue(json.get("ntpServiceActive").getAsBoolean());
		assertEquals("2026-10-06T14:00:00+07:00", json.get("currentTime").getAsString());
		assertFalse(json.get("serverReachable").getAsBoolean());
		assertEquals(0L, json.get("packetCount").getAsLong());
		assertEquals(13, json.size());
	}
}
