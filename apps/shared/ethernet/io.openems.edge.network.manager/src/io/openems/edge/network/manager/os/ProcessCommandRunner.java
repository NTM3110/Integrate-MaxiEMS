package io.openems.edge.network.manager.os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class ProcessCommandRunner implements CommandRunner {

	private final Duration timeout;

	public ProcessCommandRunner(Duration timeout) {
		this.timeout = timeout;
	}

	@Override
	public String execute(List<String> command) throws IOException, InterruptedException {
		var process = new ProcessBuilder(command).start();
		var stdoutFuture = CompletableFuture.supplyAsync(() -> readAllBytes(process.getInputStream()));
		var stderrFuture = CompletableFuture.supplyAsync(() -> readAllBytes(process.getErrorStream()));
		if (!process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			throw new IOException("Command timed out: " + command.get(0));
		}
		var stdout = await(stdoutFuture).trim();
		var stderr = await(stderrFuture).trim();
		if (process.exitValue() != 0) {
			throw new IOException("Command failed with exit code " + process.exitValue() + ": " + stderr);
		}
		return stdout;
	}

	private static String readAllBytes(java.io.InputStream stream) {
		try {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new CompletionException(e);
		}
	}

	private static String await(CompletableFuture<String> future) throws IOException {
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof IOException ioException) {
				throw ioException;
			}
			throw e;
		}
	}
}
