package io.openems.edge.bms.service.time.implement;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bms.service.time.contracts.BmsTimeService;
import io.openems.edge.bms.service.time.model.BmsTimeStatus;

@Component(name = "BMS.Time", immediate = true)
public class BmsTimeServiceImpl implements BmsTimeService {
	private static final String HELPER = "/usr/local/sbin/maxiems-time-helper";

	@Override
	public BmsTimeStatus status() throws OpenemsException {
		return parse(this.run("/usr/bin/sudo", "-n", HELPER, "status"));
	}

	@Override
	public BmsTimeStatus configure(String serverIp) throws OpenemsException {
		validateIp(serverIp);
		return parse(this.run("/usr/bin/sudo", "-n", HELPER, "configure", serverIp));
	}

	static void validateIp(String value) throws OpenemsException {
		try {
			if (value == null || value.isEmpty()) {
				throw new IllegalArgumentException();
			}
			if (value.contains(":")) {
				if (!value.matches("[0-9a-fA-F:.]+")) {
					throw new IllegalArgumentException();
				}
				InetAddress.getByName(value); // Character allowlist prevents DNS resolution.
			} else {
				var parts = value.split("\\.", -1);
				if (parts.length != 4) {
					throw new IllegalArgumentException();
				}
				for (var part : parts) {
					if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
						throw new IllegalArgumentException();
					}
				}
			}
		} catch (Exception e) {
			throw new OpenemsException("serverIp must be one IPv4 or IPv6 literal");
		}
	}

	protected String run(String... command) throws OpenemsException {
		java.nio.file.Path output = null;
		Process process = null;
		try {
			output = Files.createTempFile("bms-time-", ".json");
			process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
			if (!process.waitFor(90, TimeUnit.SECONDS)) {
				throw new OpenemsException("Time synchronization command timed out");
			}
			if (process.exitValue() != 0) {
				throw new OpenemsException("Time synchronization helper failed");
			}
			if (Files.size(output) > 16384) {
				throw new OpenemsException("Invalid time synchronization response");
			}
			return Files.readString(output);
		} catch (IOException e) {
			throw new OpenemsException("Unable to execute time synchronization helper");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Time synchronization command interrupted");
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			if (output != null) {
				try {
					Files.deleteIfExists(output);
				} catch (IOException ignored) {
					// Best effort cleanup.
				}
			}
		}
	}

	static BmsTimeStatus parse(String output) throws OpenemsException {
		try {
			var json = JsonParser.parseString(output).getAsJsonObject();
			return new BmsTimeStatus(string(json, "configuredServer"), string(json, "serverName"),
					string(json, "serverAddress"), bool(json, "synchronized"), bool(json, "ntpServiceActive"),
					string(json, "timezone"), string(json, "serviceState"), string(json, "currentTime"),
					bool(json, "serverReachable"), string(json, "reachabilityError"), longValue(json, "packetCount"),
					string(json, "pollInterval"), string(json, "error"));
		} catch (RuntimeException e) {
			throw new OpenemsException("Invalid time synchronization response");
		}
	}

	private static String string(JsonObject json, String key) {
		var value = json.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static Boolean bool(JsonObject json, String key) {
		var value = json.get(key);
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException();
		}
		return value.getAsBoolean();
	}

	private static Long longValue(JsonObject json, String key) {
		var value = json.get(key);
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException();
		}
		return value.getAsLong();
	}
}
