package io.openems.edge.network.manager.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

import io.openems.edge.network.manager.Mode;

public class NetworkOperationStoreTest {

	private static final Instant CREATED_AT = Instant.parse("2026-08-07T10:28:30Z");

	@Test
	public void acceptedOperationAdvancesSequenceAndKeepsImmutableSnapshots() {
		var store = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24,
				"192.168.10.1", List.of("8.8.8.8"));

		var accepted = store.create("network-42", 12, config, CREATED_AT, List.of("192.168.1.20"), 60);
		var applying = store.transition("network-42", NetworkOperationState.APPLYING, CREATED_AT.plusSeconds(1));

		assertEquals(1L, accepted.sequence());
		assertEquals(NetworkOperationState.ACCEPTED, accepted.state());
		assertEquals(2L, applying.sequence());
		assertEquals(NetworkOperationState.APPLYING, applying.state());
		assertEquals(NetworkOperationState.ACCEPTED, accepted.state());
	}

	@Test
	public void terminalOperationCannotTransitionAgain() {
		var store = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth1", Mode.DHCP, null, null, null, List.of());
		store.create("network-43", 13, config, CREATED_AT, List.of(), 60);
		var cancelled = store.transition("network-43", NetworkOperationState.CANCELLED, CREATED_AT.plusSeconds(1));

		assertTrue(cancelled.state().isTerminal());
		assertThrows(IllegalStateException.class,
				() -> store.transition("network-43", NetworkOperationState.APPLYING, CREATED_AT.plusSeconds(2)));
	}

	@Test
	public void failedSnapshotCarriesStructuredError() {
		var store = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24, null, List.of());
		store.create("network-44", 14, config, CREATED_AT, List.of(), 60);
		var error = new NetworkOperationError(NetworkErrorCode.ACTIVATION_FAILED, NetworkOperationStage.ACTIVATE,
				"NetworkManager could not activate the connection profile", false, NetworkOperationState.ROLLED_BACK);

		var failed = store.fail("network-44", error, CREATED_AT.plusSeconds(2));

		assertEquals(NetworkOperationState.FAILED, failed.state());
		assertEquals(error, failed.error());
		assertTrue(failed.state().isTerminal());
		assertFalse(NetworkOperationState.ROLLING_BACK.isTerminal());
	}

	@Test
	public void doesNotCreateOperationWhenDurableListenerFails() {
		var store = new NetworkOperationStore(snapshot -> {
			throw new IllegalStateException("persistence failed");
		});
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());

		assertThrows(IllegalStateException.class,
				() -> store.create("network-45", 15, config, CREATED_AT, List.of(), 60));
		assertThrows(IllegalArgumentException.class, () -> store.get("network-45"));
	}

	@Test
	public void reportsStableCodesForStoreBoundaryErrors() {
		var store = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());
		store.create("network-46", 16, config, CREATED_AT, List.of(), 60);

		var inProgress = assertThrows(IllegalStateException.class,
				() -> store.create("network-47", 17, config, CREATED_AT, List.of(), 60));
		var notFound = assertThrows(IllegalArgumentException.class, () -> store.get("network-missing"));

		assertTrue(inProgress.getMessage().startsWith(NetworkErrorCode.OPERATION_IN_PROGRESS.name()));
		assertTrue(notFound.getMessage().startsWith(NetworkErrorCode.OPERATION_NOT_FOUND.name()));
	}
}
