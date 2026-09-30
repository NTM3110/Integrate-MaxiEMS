package io.openems.edge.bms.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
