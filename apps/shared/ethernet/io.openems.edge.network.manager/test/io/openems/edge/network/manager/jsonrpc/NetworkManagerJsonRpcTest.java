package io.openems.edge.network.manager.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;
import io.openems.edge.network.manager.operation.NetworkOperationSnapshot;
import io.openems.edge.network.manager.operation.NetworkOperationState;

public class NetworkManagerJsonRpcTest {

	@Test
	public void setRequestRoundTripsFullInterfaceConfiguration() {
		var request = new SetNetworkManagerConfig.Request("eth0", Mode.STATIC, "192.168.10.20", 24,
				"192.168.10.1", List.of("8.8.8.8", "1.1.1.1"), 11);

		var roundTrip = SetNetworkManagerConfig.Request.serializer()
				.deserialize(SetNetworkManagerConfig.Request.serializer().serialize(request));

		assertEquals(request, roundTrip);
		assertEquals(NetworkOperationSnapshot.serializer().descriptor().getClass(),
				new SetNetworkManagerConfig().getResponseSerializer().descriptor().getClass());
	}

	@Test
	public void operationSnapshotRoundTripsNullableFields() {
		var createdAt = Instant.parse("2026-08-07T10:28:30Z");
		var config = new NetworkInterfaceConfig("eth1", Mode.DHCP, null, null, null, List.of());
		var snapshot = new NetworkOperationSnapshot("network-42", 1, 12, NetworkOperationState.ACCEPTED, config,
				createdAt, createdAt, 60, null, null, List.of("192.168.1.20"), List.of(), false, false, true, null);

		var roundTrip = NetworkOperationSnapshot.serializer()
				.deserialize(NetworkOperationSnapshot.serializer().serialize(snapshot));

		assertEquals(snapshot, roundTrip);
		assertNull(roundTrip.confirmationDeadline());
		assertNull(roundTrip.error());
	}

	@Test
	public void operationSnapshotDoesNotExposeCheckpointId() {
		var createdAt = Instant.parse("2026-08-07T10:28:30Z");
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());
		var snapshot = new NetworkOperationSnapshot("network-42", 3, 12, NetworkOperationState.APPLYING, config,
				createdAt, createdAt, 60, createdAt.plusSeconds(60), "/org/freedesktop/NetworkManager/Checkpoint/1",
				List.of("192.168.1.20"), List.of(), false, false, true, null);

		var json = NetworkOperationSnapshot.serializer().serialize(snapshot).getAsJsonObject();

		assertFalse(json.has("checkpointId"));
	}

	@Test
	public void persistenceSnapshotRetainsCheckpointId() {
		var createdAt = Instant.parse("2026-08-07T10:28:30Z");
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());
		var snapshot = new NetworkOperationSnapshot("network-42", 3, 12, NetworkOperationState.APPLYING, config,
				createdAt, createdAt, 60, createdAt.plusSeconds(60), "/org/freedesktop/NetworkManager/Checkpoint/1",
				List.of("192.168.1.20"), List.of(), false, false, true, null);

		var roundTrip = NetworkOperationSnapshot.persistenceSerializer()
				.deserialize(NetworkOperationSnapshot.persistenceSerializer().serialize(snapshot));

		assertEquals(snapshot, roundTrip);
	}

	@Test
	public void operationEndpointsUseTypedRequests() {
		var get = new GetNetworkManagerOperation.Request("network-42");
		var confirm = new ConfirmNetworkManagerConfig.Request("network-42", 5);
		var cancel = new CancelNetworkManagerConfig.Request("network-42");

		assertEquals(get, GetNetworkManagerOperation.Request.serializer()
				.deserialize(GetNetworkManagerOperation.Request.serializer().serialize(get)));
		assertEquals(confirm, ConfirmNetworkManagerConfig.Request.serializer()
				.deserialize(ConfirmNetworkManagerConfig.Request.serializer().serialize(confirm)));
		assertEquals(cancel, CancelNetworkManagerConfig.Request.serializer()
				.deserialize(CancelNetworkManagerConfig.Request.serializer().serialize(cancel)));
	}

	@Test
	public void getConfigResponseRoundTripsDesiredAndObservedState() {
		var desired = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());
		var observed = new GetNetworkManagerConfig.ObservedInterface("ACTIVATED", true,
				List.of("192.168.1.20/24"), "192.168.1.1", List.of("192.168.1.1"), "openems-eth0");
		var response = new GetNetworkManagerConfig.Response(12,
				List.of(new GetNetworkManagerConfig.InterfaceEntry("eth0", desired, observed)), null);

		var roundTrip = GetNetworkManagerConfig.Response.serializer()
				.deserialize(GetNetworkManagerConfig.Response.serializer().serialize(response));

		assertEquals(response, roundTrip);
	}
}
