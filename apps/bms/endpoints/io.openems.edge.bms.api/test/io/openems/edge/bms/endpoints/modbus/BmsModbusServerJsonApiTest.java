package io.openems.edge.bms.endpoints.modbus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;
import com.google.gson.JsonObject;

import io.openems.edge.bms.service.auth.contracts.PlatformAuthenticationService;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.RuntimeState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.SerialPort;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.ServerState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;

public class BmsModbusServerJsonApiTest {
	@Test
	public void authenticatesBeforeParsingAndRequiresAdministratorForUpdate() throws Exception {
		var api = new BmsModbusServerJsonApi();
		var authentication = mock(PlatformAuthenticationService.class);
		var field = BmsModbusServerJsonApi.class.getDeclaredField("authentication");
		field.setAccessible(true);
		field.set(api, authentication);
		var params = new JsonObject();
		assertThrows(SecurityException.class, () -> api.authorize(params, false));
		params.addProperty("token", "token");
		when(authentication.authenticateToken("token")).thenReturn(Optional.of(principal(PlatformRole.BMS_VIEWER)));
		api.authorize(params, false);
		assertThrows(SecurityException.class, () -> api.authorize(params, true));
		when(authentication.authenticateToken("token")).thenReturn(Optional.of(principal(PlatformRole.PLATFORM_ADMIN)));
		api.authorize(params, true);
	}

	@Test
	public void serialResponseUsesLogicalPortsAndAuthoritativeRuntime() {
		var state = new ServerState(true, Transport.SERIAL_RTU, 7, "0.0.0.0", 1502, "modbus1", RuntimeState.ERROR,
				"Device is busy", List.of(new SerialPort("modbus1", "Port 2", "/dev/ttyS7", "9600 8N1",
						false, true, "MODBUS_SERVER", List.of())));
		var json = BmsModbusServerJsonApi.json(state);
		assertEquals("SERIAL_RTU", json.get("transport").getAsString());
		assertEquals("ERROR", json.get("runtimeState").getAsString());
		assertEquals("0.0.0.0", json.get("bindAddress").getAsString());
		assertEquals("Device is busy", json.get("runtimeError").getAsString());
		var port = json.getAsJsonArray("ports").get(0).getAsJsonObject();
		assertEquals("Port 2", port.get("displayName").getAsString());
		assertEquals("/dev/ttyS7", port.get("portName").getAsString());
		assertTrue(port.get("selected").getAsBoolean());
	}

	private static AuthenticatedPrincipal principal(PlatformRole role) {
		return new AuthenticatedPrincipal(UUID.randomUUID(), "user", "User", Set.of(role));
	}
}
