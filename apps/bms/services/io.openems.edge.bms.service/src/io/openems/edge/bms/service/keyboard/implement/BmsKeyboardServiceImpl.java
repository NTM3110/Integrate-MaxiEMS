package io.openems.edge.bms.service.keyboard.implement;

import io.openems.edge.bms.service.keyboard.contracts.BmsKeyboardService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;

@Designate(ocd = Config.class)
@Component(name = "BMS.Keyboard", immediate = true)
public class BmsKeyboardServiceImpl implements BmsKeyboardService {
	private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
	private volatile Config config;

	@Activate
	@Modified
	private void activate(Config config) {
		this.config = config;
	}

	@Override
	public void command(Action action) throws OpenemsException {
		var path = Path.of(this.config.helperPath());
		if (!Files.isRegularFile(path)) throw new OpenemsException("Keyboard helper was not found: " + path);
		try {
			var process = new ProcessBuilder("sudo", "-n", path.toString(), action.wireName()).redirectErrorStream(true).start();
			if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new OpenemsException("Keyboard command timed out");
			}
			var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (process.exitValue() != 0) throw new OpenemsException("Keyboard command failed: " + output);
		} catch (IOException e) {
			throw new OpenemsException("Unable to execute keyboard command: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Keyboard command was interrupted");
		}
	}
}
