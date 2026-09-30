package io.openems.edge.network.manager.transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.Test;

import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;
import io.openems.edge.network.manager.operation.NetworkErrorCode;
import io.openems.edge.network.manager.operation.NetworkOperationStage;
import io.openems.edge.network.manager.operation.NetworkOperationState;
import io.openems.edge.network.manager.operation.NetworkOperationStore;
import io.openems.edge.network.manager.os.NetworkProfile;
import io.openems.edge.network.manager.os.NetworkSystemAdapter;
import io.openems.edge.network.manager.os.NetworkVerification;
import io.openems.edge.network.manager.os.ObservedNetworkState;

public class NetworkTransactionServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-07T10:28:30Z");

	@Test
	public void acceptsBeforeApplyingAndConfirmsVerifiedConfiguration() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-42", 60);
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24,
				"192.168.10.1", List.of("8.8.8.8"));

		var accepted = service.submit(12, config, List.of("192.168.1.20"));

		assertEquals(NetworkOperationState.ACCEPTED, accepted.state());
		assertFalse(adapter.applied);

		executor.runNext();
		var awaiting = service.get("network-42");
		assertEquals(NetworkOperationState.AWAITING_CONFIRMATION, awaiting.state());
		assertTrue(awaiting.profileVerified());
		assertTrue(awaiting.runtimeVerified());

		var applied = service.confirm("network-42", awaiting.sequence());
		assertEquals(NetworkOperationState.APPLIED, applied.state());
		assertEquals(applied, service.confirm("network-42", awaiting.sequence()));
		assertTrue(adapter.checkpointCommitted);
	}

	@Test
	public void rollsBackWhenApplyFails() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		adapter.failApply = true;
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-43", 60);
		var config = new NetworkInterfaceConfig("eth1", Mode.DHCP, null, null, null, List.of());

		service.submit(13, config, List.of());
		executor.runNext();

		var failed = service.get("network-43");
		assertEquals(NetworkOperationState.FAILED, failed.state());
		assertTrue(adapter.rolledBack);
		assertEquals(NetworkOperationState.ROLLED_BACK, failed.error().rollbackState());
		assertEquals(NetworkErrorCode.PROFILE_UPDATE_FAILED, failed.error().code());
		assertEquals(NetworkOperationStage.APPLY, failed.error().stage());
	}

	@Test
	public void reportsActivationFailureAtActivationStage() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		adapter.failActivation = true;
		adapter.verification = new NetworkVerification(true, false, true);
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-49", 60);
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());

		service.submit(19, config, List.of());
		executor.runNext();
		var failed = service.get("network-49");

		assertEquals(NetworkErrorCode.ACTIVATION_FAILED, failed.error().code());
		assertEquals(NetworkOperationStage.ACTIVATE, failed.error().stage());
	}

	@Test
	public void commitsVerifiedProfileWhenLinkIsUnavailable() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		adapter.verification = new NetworkVerification(true, false, false);
		adapter.failActivation = true;
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-44", 60);
		var config = new NetworkInterfaceConfig("eth1", Mode.STATIC, "10.0.0.5", 24, null, List.of());

		service.submit(14, config, List.of());
		executor.runNext();

		assertEquals(NetworkOperationState.CONFIGURED_WAITING_FOR_LINK, service.get("network-44").state());
		assertTrue(adapter.checkpointCommitted);
	}

	@Test
	public void expiresUnconfirmedOperationAndRecordsRollback() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-45", 60);
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());

		service.submit(15, config, List.of());
		executor.runNext();
		var expired = service.expire("network-45", NOW.plusSeconds(61));

		assertEquals(NetworkOperationState.ROLLED_BACK, expired.state());
		assertEquals(NetworkErrorCode.CONFIRMATION_TIMEOUT, expired.error().code());
		assertEquals(NetworkOperationStage.ROLLBACK, expired.error().stage());
		assertEquals(NetworkOperationState.ROLLED_BACK, expired.error().rollbackState());
		assertFalse(adapter.rolledBack);
	}

	@Test
	public void cancelRollsBackActiveCheckpoint() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-47", 60);
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());

		service.submit(17, config, List.of());
		executor.runNext();
		var cancelled = service.cancel("network-47");

		assertEquals(NetworkOperationState.ROLLED_BACK, cancelled.state());
		assertTrue(adapter.rolledBack);
	}

	@Test
	public void cancelBeforeWorkerStartsPreventsNetworkChanges() {
		var executor = new QueuedExecutor();
		var adapter = new FakeAdapter();
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, executor,
				Clock.fixed(NOW, ZoneOffset.UTC), () -> "network-48", 60);
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of());

		service.submit(18, config, List.of());
		assertEquals(NetworkOperationState.CANCELLED, service.cancel("network-48").state());
		executor.runNext();

		assertEquals(NetworkOperationState.CANCELLED, service.get("network-48").state());
		assertFalse(adapter.applied);
	}

	@Test
	public void recoversInterruptedOperationAsRolledBackAfterRestart() {
		var store = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24, null, List.of());
		var interrupted = store.create("network-46", 16, config, NOW, List.of("192.168.1.20"), 60);
		var recoveredStore = new NetworkOperationStore();
		var service = new NetworkTransactionService(recoveredStore, new FakeAdapter(), new QueuedExecutor(),
				Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC), () -> "unused", 60);

		var recovered = service.recoverAfterRestart(interrupted);

		assertEquals("network-46", recovered.operationId());
		assertEquals(NetworkOperationState.ROLLED_BACK, recovered.state());
		assertEquals(recovered, service.get("network-46"));
	}

	@Test
	public void keepsCheckpointedOperationUntilAutomaticRollbackDeadline() {
		var originalStore = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24, null, List.of());
		originalStore.create("network-50", 20, config, NOW, List.of("192.168.1.20"), 60);
		originalStore.transition("network-50", NetworkOperationState.APPLYING, NOW);
		var checkpointed = originalStore.withCheckpoint("network-50", "checkpoint-1", NOW.plusSeconds(60), NOW);
		var recoveredStore = new NetworkOperationStore();
		var service = new NetworkTransactionService(recoveredStore, new FakeAdapter(), new QueuedExecutor(),
				Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC), () -> "unused", 60);

		var recovered = service.recoverAfterRestart(checkpointed);

		assertEquals(NetworkOperationState.APPLYING, recovered.state());
		assertEquals(NOW.plusSeconds(60), recovered.confirmationDeadline());
		assertEquals(NetworkOperationState.ROLLED_BACK,
				service.expire("network-50", NOW.plusSeconds(61)).state());
	}

	@Test
	public void confirmsCheckpointAfterRestart() {
		var originalStore = new NetworkOperationStore();
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24, null, List.of());
		originalStore.create("network-51", 21, config, NOW, List.of("192.168.1.20"), 60);
		originalStore.transition("network-51", NetworkOperationState.APPLYING, NOW);
		originalStore.withCheckpoint("network-51", "checkpoint-1", NOW.plusSeconds(60), NOW);
		originalStore.transition("network-51", NetworkOperationState.VERIFYING, NOW);
		var awaiting = originalStore.verified("network-51", true, true, true, NOW.plusSeconds(60), NOW);
		var adapter = new FakeAdapter();
		var service = new NetworkTransactionService(new NetworkOperationStore(), adapter, new QueuedExecutor(),
				Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC), () -> "unused", 60);

		var recovered = service.recoverAfterRestart(awaiting);
		var applied = service.confirm("network-51", recovered.sequence());

		assertEquals(NetworkOperationState.APPLIED, applied.state());
		assertTrue(adapter.checkpointCommitted);
	}

	private static final class QueuedExecutor implements Executor {
		private final Queue<Runnable> commands = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			this.commands.add(command);
		}

		private void runNext() {
			this.commands.remove().run();
		}
	}

	private static final class FakeAdapter implements NetworkSystemAdapter {
		private boolean applied;
		private boolean failApply;
		private boolean rolledBack;
		private boolean checkpointCommitted;
		private boolean failActivation;
		private NetworkVerification verification = new NetworkVerification(true, true, true);

		@Override
		public NetworkProfile ensureProfile(String interfaceName) {
			return new NetworkProfile("uuid-" + interfaceName, interfaceName);
		}

		@Override
		public String createCheckpoint(String interfaceName, int timeoutSeconds) {
			return "checkpoint-1";
		}

		@Override
		public void apply(NetworkProfile profile, NetworkInterfaceConfig config) throws Exception {
			if (this.failApply) {
				throw new Exception("apply failed");
			}
			this.applied = true;
		}

		@Override
		public void activate(NetworkProfile profile, String interfaceName) throws Exception {
			if (this.failActivation) {
				throw new Exception("carrier unavailable");
			}
		}

		@Override
		public NetworkVerification verify(NetworkProfile profile, NetworkInterfaceConfig config) {
			return this.verification;
		}

		@Override
		public void commitCheckpoint(String checkpointId) {
			this.checkpointCommitted = true;
		}

		@Override
		public void rollbackCheckpoint(String checkpointId) {
			this.rolledBack = true;
		}

		@Override
		public ObservedNetworkState observe(String interfaceName) {
			return new ObservedNetworkState("DISCONNECTED", false, List.of(), null, List.of(), null);
		}
	}
}
