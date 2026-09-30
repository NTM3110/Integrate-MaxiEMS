package io.openems.edge.core.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;

/** Production network transaction collaborator for systemd-networkd. */
public final class SystemdNetworkApplyCollaborator implements NetworkApplyCollaborator {

	private static final Path DEFAULT_STAGING_ROOT = Path.of("/var/lib/maxiems/shared/network-staging");
	private static final String HELPER = "/usr/local/libexec/maxiems-network-helper";
	private static final Pattern INTERFACE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,14}$");
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	/** Executes one fixed helper argv. */
	@FunctionalInterface
	public interface CommandRunner {
		CommandResult run(List<String> arguments, Duration timeout) throws IOException, InterruptedException;
	}

	/** Result of one helper process. */
	public record CommandResult(int exitCode, String standardError, boolean timedOut) {
	}

	private final Path stagingRoot;
	private final CommandRunner runner;
	private final Duration timeout;

	/** Creates the production collaborator. */
	public SystemdNetworkApplyCollaborator() {
		this(DEFAULT_STAGING_ROOT, SystemdNetworkApplyCollaborator::runProcess, DEFAULT_TIMEOUT);
	}

	SystemdNetworkApplyCollaborator(Path stagingRoot, CommandRunner runner, Duration timeout) {
		this.stagingRoot = stagingRoot;
		this.runner = runner;
		this.timeout = timeout;
	}

	@Override
	public void apply(Map<String, List<String>> configurations) throws OpenemsNamedException {
		if (configurations.isEmpty()) {
			throw new OpenemsException("Received no network files to apply");
		}
		var transactionId = UUID.randomUUID().toString();
		var transactionPath = this.stagingRoot.resolve(transactionId);
		try {
			Files.createDirectory(transactionPath);
			for (var entry : configurations.entrySet()) {
				if (!INTERFACE_PATTERN.matcher(entry.getKey()).matches()) {
					throw new OpenemsException("Invalid network interface name");
				}
				var candidate = transactionPath.resolve("70-maxiems-" + entry.getKey() + ".network");
				if (!candidate.normalize().getParent().equals(transactionPath)
						|| Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
					throw new OpenemsException("Unsafe network staging path");
				}
				Files.write(candidate, entry.getValue(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			}
			this.invoke("validate", transactionId);
			try {
				this.invoke("apply", transactionId);
				this.invoke("confirm", transactionId);
			} catch (OpenemsNamedException e) {
				this.tryRollback(transactionId);
				throw e;
			}
		} catch (IOException e) {
			throw new OpenemsException("Unable to stage network transaction: " + e.getMessage());
		}
	}

	private void invoke(String operation, String transactionId) throws OpenemsNamedException {
		var arguments = List.of("/usr/bin/sudo", "-n", HELPER, operation, transactionId);
		final CommandResult result;
		try {
			result = this.runner.run(arguments, this.timeout);
		} catch (IOException e) {
			throw new OpenemsException("Network helper failed to start: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Network helper was interrupted");
		}
		if (result.timedOut()) {
			throw new OpenemsException("Network helper timed out during " + operation);
		}
		if (result.exitCode() != 0) {
			throw new OpenemsException("Network helper " + operation + " failed: " + result.standardError());
		}
	}

	private void tryRollback(String transactionId) {
		try {
			this.invoke("rollback", transactionId);
		} catch (OpenemsNamedException ignored) {
			// The root helper's timer remains the final rollback guard.
		}
	}

	private static CommandResult runProcess(List<String> arguments, Duration timeout)
			throws IOException, InterruptedException {
		var process = new ProcessBuilder(new ArrayList<>(arguments)).start();
		if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			return new CommandResult(-1, "timeout", true);
		}
		var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return new CommandResult(process.exitValue(), stderr, false);
	}
}
