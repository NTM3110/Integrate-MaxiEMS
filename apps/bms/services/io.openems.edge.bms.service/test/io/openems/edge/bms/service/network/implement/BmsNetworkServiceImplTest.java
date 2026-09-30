package io.openems.edge.bms.service.network.implement;

import io.openems.edge.bms.service.network.model.BmsNetworkModels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class BmsNetworkServiceImplTest {

	@Test
	public void parsesPhysicalConnectionState() {
		assertTrue(BmsNetworkServiceImpl.isConnectedState("100 (connected)"));
		assertTrue(BmsNetworkServiceImpl.isConnectedState("100"));
		assertFalse(BmsNetworkServiceImpl.isConnectedState("30 (disconnected)"));
		assertFalse(BmsNetworkServiceImpl.isConnectedState(null));
	}

	@Test
	public void resolvesProfilesWithoutSilentlyChoosingDuplicateNames() {
		var profiles = "bms-eth0:uuid-one\nuser:profile:uuid-user\nbms-eth0:uuid-two\n";

		assertEquals(List.of("uuid-one", "uuid-two"), BmsNetworkServiceImpl.profileUuids(profiles, "bms-eth0"));
		assertEquals(List.of("uuid-user"), BmsNetworkServiceImpl.profileUuids(profiles, "user:profile"));
		assertEquals(List.of(), BmsNetworkServiceImpl.profileUuids(profiles, "missing"));
	}

	@Test
	public void normalizesNetworkManagerModeForTheApi() {
		assertEquals("DHCP", BmsNetworkServiceImpl.apiMode("auto\n"));
		assertEquals("STATIC", BmsNetworkServiceImpl.apiMode("MANUAL"));
		assertEquals("UNKNOWN", BmsNetworkServiceImpl.apiMode("disabled"));
		assertEquals("UNKNOWN", BmsNetworkServiceImpl.apiMode(null));
	}

	@Test
	public void acceptsStaticConfigurationWithoutGateway() throws Exception {
		var update = new BmsNetworkModels.NetworkUpdate("STATIC", "192.168.10.2", "255.255.255.0", "", "");

		assertEquals(List.of("sudo", "-n", "/usr/lib/maxiems/netcfg.sh", "eth1", "static", "192.168.10.2",
				"255.255.255.0", ""),
				BmsNetworkServiceImpl.applyCommand(Path.of("/usr/lib/maxiems/netcfg.sh"), "eth1", update));
	}

	@Test
	public void preservesExplicitStaticGatewayAndDns() throws Exception {
		var update = new BmsNetworkModels.NetworkUpdate("static", "192.168.10.2", "24", "192.168.10.1",
				"1.1.1.1, 8.8.8.8");

		assertEquals(List.of("sudo", "-n", "/usr/lib/maxiems/netcfg.sh", "eth1", "static", "192.168.10.2",
				"24", "192.168.10.1", "1.1.1.1", "8.8.8.8"),
				BmsNetworkServiceImpl.applyCommand(Path.of("/usr/lib/maxiems/netcfg.sh"), "eth1", update));
	}
}
