package io.openems.edge.bms.service.system.implement;

import io.openems.edge.bms.service.system.contracts.BmsSystemService;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;

/** Runs only an exact sudoers-approved systemctl reboot command. */
@Designate(ocd = Config.class)
@Component(name = "BMS.System", immediate = true)
public class BmsSystemServiceImpl implements BmsSystemService {

	private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
	private volatile Config config;

	@Activate
	@Modified
	private void activate(Config config) {
		this.config = config;
	}

	@Override
	public void reboot() throws OpenemsException {
		var systemctl = this.config.systemctlPath();
		if (!"/usr/bin/systemctl".equals(systemctl)) {
			throw new OpenemsException("Unsupported systemctl command path");
		}
		this.run("sudo", "-n", systemctl, "reboot", "--no-block");
	}

	private void run(String... command) throws OpenemsException {
		try {
			var process = new ProcessBuilder(command).redirectErrorStream(true).start();
			if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new OpenemsException("Reboot command timed out");
			}
			var output = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
			if (process.exitValue() != 0) {
				throw new OpenemsException(output.isBlank() ? "Reboot command was rejected" : output);
			}
		} catch (IOException e) {
			throw new OpenemsException("Unable to execute reboot command: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Reboot command was interrupted");
		}
	}
}
