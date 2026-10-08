package io.openems.edge.core.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.openems.edge.core.host.SystemdNetworkApplyCollaborator.CommandResult;

public class SystemdNetworkApplyCollaboratorTest {

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void testStagesAndInvokesExactArgumentVectors() throws Exception {
		var invocations = new ArrayList<List<String>>();
		var root = this.temporaryFolder.newFolder("staging").toPath();
		var collaborator = new SystemdNetworkApplyCollaborator(root, (arguments, timeout) -> {
			invocations.add(arguments);
			return new CommandResult(0, "", false);
		}, Duration.ofSeconds(5));

		collaborator.apply(Map.of("eth0", List.of("[Match]", "Name=eth0", "", "[Network]", "DHCP=yes")));

		assertEquals(3, invocations.size());
		assertEquals("validate", invocations.get(0).get(3));
		assertEquals("apply", invocations.get(1).get(3));
		assertEquals("confirm", invocations.get(2).get(3));
		var transactionId = invocations.get(0).get(4);
		assertTrue(invocations.stream().allMatch(argv -> argv.equals(List.of(//
				"/usr/bin/sudo", "-n", "/usr/local/sbin/maxiems-network-helper", argv.get(3), transactionId))));
		assertTrue(Files.isRegularFile(root.resolve(transactionId).resolve("70-maxiems-eth0.network")));
		assertFalse(invocations.stream().flatMap(List::stream).anyMatch(value -> value.contains("/bin/bash")));
	}

	@Test
	public void testApplyFailureAndTimeoutRequestRollback() throws Exception {
		var operations = new ArrayList<String>();
		var failureCollaborator = this.collaborator((arguments, timeout) -> {
			var operation = arguments.get(3);
			operations.add(operation);
			return operation.equals("apply") ? new CommandResult(17, "fixture failure", false)
					: new CommandResult(0, "", false);
		});
		assertThrows(Exception.class,
				() -> failureCollaborator.apply(Map.of("eth0", List.of("[Match]", "Name=eth0"))));
		assertEquals(List.of("validate", "apply", "rollback"), operations);

		operations.clear();
		var collaborator = this.collaborator((arguments, timeout) -> {
			var operation = arguments.get(3);
			operations.add(operation);
			return operation.equals("apply") ? new CommandResult(-1, "", true) : new CommandResult(0, "", false);
		});
		var timeoutCollaborator = collaborator;
		assertThrows(Exception.class,
				() -> timeoutCollaborator.apply(Map.of("eth0", List.of("[Match]", "Name=eth0"))));
		assertEquals(List.of("validate", "apply", "rollback"), operations);
	}

	@Test
	public void testConfirmFailureRequestsRollback() throws Exception {
		var operations = new ArrayList<String>();
		var collaborator = this.collaborator((arguments, timeout) -> {
			var operation = arguments.get(3);
			operations.add(operation);
			return operation.equals("confirm") ? new CommandResult(23, "confirm failed", false)
					: new CommandResult(0, "", false);
		});

		assertThrows(Exception.class, () -> collaborator.apply(Map.of("eth0", List.of("[Match]", "Name=eth0"))));
		assertEquals(List.of("validate", "apply", "confirm", "rollback"), operations);
	}

	@Test
	public void testConcurrentTransactionRejectionStopsBeforeApply() throws Exception {
		var operations = new ArrayList<String>();
		var collaborator = this.collaborator((arguments, timeout) -> {
			var operation = arguments.get(3);
			operations.add(operation);
			return operation.equals("validate") ? new CommandResult(24, "another network transaction is active", false)
					: new CommandResult(0, "", false);
		});

		assertThrows(Exception.class, () -> collaborator.apply(Map.of("eth0", List.of("[Match]", "Name=eth0"))));
		assertEquals(List.of("validate"), operations);
	}

	@Test
	public void testMaliciousInterfaceAndNoChangeNeverInvokeHelper() throws Exception {
		var invoked = new boolean[1];
		var collaborator = this.collaborator((arguments, timeout) -> {
			invoked[0] = true;
			return new CommandResult(0, "", false);
		});

		assertThrows(Exception.class, () -> collaborator.apply(Map.of("eth0;id", List.of("[Match]"))));
		assertThrows(Exception.class, () -> collaborator.apply(Map.of()));
		assertFalse(invoked[0]);
	}

	private SystemdNetworkApplyCollaborator collaborator(SystemdNetworkApplyCollaborator.CommandRunner runner)
			throws Exception {
		var root = Path.of(this.temporaryFolder.newFolder().getAbsolutePath());
		return new SystemdNetworkApplyCollaborator(root, runner, Duration.ofMillis(50));
	}
}
