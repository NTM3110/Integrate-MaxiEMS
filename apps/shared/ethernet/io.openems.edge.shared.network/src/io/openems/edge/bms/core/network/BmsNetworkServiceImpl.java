package io.openems.edge.bms.core.network;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bms.core.network.BmsNetworkModels.NetworkPort;
import io.openems.edge.bms.core.network.BmsNetworkModels.NetworkUpdate;

/** NetworkManager-backed implementation for the explicitly configured BMS WAN ports. */
@Designate(ocd = Config.class)
@Component(name = "BMS.Network", immediate = true)
public class BmsNetworkServiceImpl implements BmsNetworkService {

	private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
	private volatile Config config;

	@Activate
	@Modified
	private void activate(Config config) {
		this.config = config;
	}

	@Override
	public List<NetworkPort> list() throws OpenemsException {
		var result = new ArrayList<NetworkPort>();
		for (var port : this.ports()) {
			result.add(this.readPort(port));
		}
		return List.copyOf(result);
	}

	@Override
	public NetworkPort update(String id, NetworkUpdate update) throws OpenemsException {
		for (var port : this.ports()) {
			if (port.id().equals(id)) {
				this.apply(port, update);
				return this.readPort(port);
			}
		}
		throw new OpenemsException("Unknown BMS network port [" + id + "]");
	}

	private List<ConfiguredPort> ports() throws OpenemsException {
		var configured = this.config;
		if (configured == null) {
			throw new OpenemsException("BMS network service is not configured");
		}
		var wan0 = port("wan0", configured.wan0Label(), configured.wan0Interface());
		var wan1 = port("wan1", configured.wan1Label(), configured.wan1Interface());
		if (wan0.interfaceName().equals(wan1.interfaceName())) {
			throw new OpenemsException("WAN 0 and WAN 1 must use different interfaces");
		}
		return List.of(wan0, wan1);
	}

	private static ConfiguredPort port(String id, String label, String interfaceName) throws OpenemsException {
		var iface = interfaceName.trim();
		if (!iface.matches("[A-Za-z0-9_.:-]+")) {
			throw new OpenemsException("Invalid " + label.trim() + " interface name");
		}
		return new ConfiguredPort(id, label.trim(), iface);
	}

	private NetworkPort readPort(ConfiguredPort port) throws OpenemsException {
		if (!Files.isDirectory(Path.of("/sys/class/net", port.interfaceName()))) {
			return new NetworkPort(port.id(), port.name(), port.interfaceName(), false, false, "Unavailable",
					"Configured interface is unavailable: " + port.interfaceName(), null, null, null, null, "unknown");
		}

		var errors = new ArrayList<String>();
		var device = this.tryCommand("nmcli", "-t", "-f", "GENERAL.STATE,GENERAL.CONNECTION", "device", "show",
				port.interfaceName());
		boolean connected = false;
		if (device.success()) {
			var state = value(device.output(), "GENERAL.STATE:");
			connected = isConnectedState(state);
		} else {
			errors.add(device.error());
		}

		var profiles = this.tryCommand("nmcli", "-t", "--escape", "no", "-f", "NAME,UUID", "connection", "show");
		String profileUuid = null;
		if (profiles.success()) {
			var managed = profileUuids(profiles.output(), "bms-" + port.interfaceName());
			if (managed.size() == 1) {
				profileUuid = managed.get(0);
			} else if (managed.size() > 1) {
				errors.add("Multiple managed NetworkManager profiles exist for " + port.interfaceName());
			}
		} else {
			errors.add(profiles.error());
		}

		var mode = "dhcp";
		if (profileUuid != null) {
			var methodResult = this.tryCommand("nmcli", "-g", "ipv4.method", "connection", "show", "uuid", profileUuid);
			if (methodResult.success()) {
				mode = switch (methodResult.output().trim().toLowerCase(Locale.ROOT)) {
				case "auto" -> "dhcp";
				case "manual" -> "static";
				default -> "unknown";
				};
			} else {
				errors.add(methodResult.error());
				mode = "unknown";
			}
		}

		String ipAddress = null;
		String subnetMask = null;
		String gateway = null;
		var dns = new ArrayList<String>();
		if (profileUuid != null) {
			var configuredAddresses = this.tryCommand("nmcli", "-t", "-f", "ipv4.addresses,ipv4.gateway,ipv4.dns",
					"connection", "show", "uuid", profileUuid);
			if (configuredAddresses.success()) {
				for (var line : configuredAddresses.output().split("\\R")) {
					var separator = line.indexOf(':');
					if (separator < 0) continue;
					var key = line.substring(0, separator).toLowerCase(Locale.ROOT);
					var configuredValue = line.substring(separator + 1).trim();
					if (configuredValue.isEmpty()) continue;
					if (key.equals("ipv4.addresses")) {
						var firstAddress = configuredValue.split(",", 2)[0];
						var slash = firstAddress.indexOf('/');
						ipAddress = slash < 0 ? firstAddress : firstAddress.substring(0, slash);
						subnetMask = slash < 0 ? null : prefixToMask(firstAddress.substring(slash + 1));
					} else if (key.equals("ipv4.gateway")) {
						gateway = configuredValue;
					} else if (key.equals("ipv4.dns")) {
						for (var server : configuredValue.split(",")) if (!server.isBlank()) dns.add(server.trim());
					}
				}
			} else {
				errors.add(configuredAddresses.error());
			}
		}
		var addresses = this.tryCommand("nmcli", "-t", "-f", "IP4.ADDRESS,IP4.GATEWAY,IP4.DNS", "device", "show",
				port.interfaceName());
		if (!addresses.success()) errors.add(addresses.error());
		for (var line : addresses.output().split("\\R")) {
			var separator = line.indexOf(':');
			if (separator < 0) continue;
			var key = line.substring(0, separator);
			var value = line.substring(separator + 1).trim();
			if (value.isEmpty()) continue;
			if (key.startsWith("IP4.ADDRESS") && ipAddress == null) {
				var slash = value.indexOf('/');
				ipAddress = slash < 0 ? value : value.substring(0, slash);
				subnetMask = slash < 0 ? null : prefixToMask(value.substring(slash + 1));
			} else if (key.equals("IP4.GATEWAY")) {
				gateway = value;
			} else if (key.startsWith("IP4.DNS")) {
				if (!dns.contains(value)) dns.add(value);
			}
		}
		var status = connected ? "Connected" : "Disconnected";
		if (profileUuid == null && errors.isEmpty()) status += " — no profile; DHCP will be created on save";
		return new NetworkPort(port.id(), port.name(), port.interfaceName(), true, connected, status,
				errors.isEmpty() ? null : String.join("; ", errors), ipAddress, subnetMask, gateway, String.join(",", dns), mode);
	}

	private void apply(ConfiguredPort port, NetworkUpdate update) throws OpenemsException {
		this.requireInterface(port.interfaceName());
		var mode = update.mode() == null ? "" : update.mode().trim().toLowerCase(Locale.ROOT);
		var args = new ArrayList<String>();
		// The Edge JVM stays unprivileged. sudoers permits only this root-owned
		// helper for the explicitly configured interface and supported modes.
		args.add("sudo");
		args.add("-n");
		args.add(this.scriptPath().toString());
		args.add(port.interfaceName());
		if (mode.equals("dhcp")) {
			args.add("dhcp");
		} else if (mode.equals("static")) {
			require(update.ipAddress(), "ipAddress");
			require(update.subnetMask(), "subnetMask");
			require(update.gateway(), "gateway");
			args.add("static");
			args.add(update.ipAddress().trim());
			args.add(update.subnetMask().trim());
			args.add(update.gateway().trim());
			for (var server : splitDns(update.dns())) args.add(server);
		} else {
			throw new OpenemsException("Mode must be DHCP or STATIC");
		}
		this.command(args.toArray(String[]::new));
	}

	private Path scriptPath() throws OpenemsException {
		var path = Path.of(this.config.netcfgScriptPath());
		if (!Files.isRegularFile(path)) throw new OpenemsException("Network configuration script was not found: " + path);
		return path;
	}

	private void requireInterface(String iface) throws OpenemsException {
		if (!Files.isDirectory(Path.of("/sys/class/net", iface))) {
			throw new OpenemsException("Configured interface is unavailable: " + iface);
		}
	}

	private String command(String... command) throws OpenemsException {
		try {
			var process = new ProcessBuilder(command).redirectErrorStream(true).start();
			if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new OpenemsException("Network command timed out");
			}
			var output = new String(process.getInputStream().readAllBytes(), UTF_8);
			if (process.exitValue() != 0) throw new OpenemsException("Network command failed: " + output.trim());
			return output;
		} catch (IOException e) {
			throw new OpenemsException("Unable to execute network command: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Network command was interrupted");
		}
	}

	private CommandResult tryCommand(String... command) {
		try {
			return new CommandResult(true, this.command(command), null);
		} catch (OpenemsException e) {
			return new CommandResult(false, "", e.getMessage());
		}
	}

	static List<String> profileUuids(String output, String profileName) {
		var result = new ArrayList<String>();
		for (var line : output.split("\\R")) {
			var separator = line.lastIndexOf(':');
			if (separator > 0 && line.substring(0, separator).equals(profileName)) {
				var uuid = line.substring(separator + 1).trim();
				if (!uuid.isEmpty()) result.add(uuid);
			}
		}
		return List.copyOf(result);
	}

	static boolean isConnectedState(String state) {
		return state != null && state.matches("^100(?:\\s.*)?$");
	}

	private static String value(String output, String key) {
		for (var line : output.split("\\R")) if (line.startsWith(key)) return line.substring(key.length()).trim();
		return null;
	}

	private static String prefixToMask(String prefix) {
		try {
			var bits = Integer.parseInt(prefix);
			if (bits < 0 || bits > 32) return null;
			var mask = bits == 0 ? 0 : -1 << (32 - bits);
			return ((mask >>> 24) & 255) + "." + ((mask >>> 16) & 255) + "." + ((mask >>> 8) & 255) + "." + (mask & 255);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void require(String value, String property) throws OpenemsException {
		if (value == null || value.isBlank()) throw new OpenemsException("Missing " + property + " for static configuration");
	}

	private static List<String> splitDns(String dns) {
		if (dns == null || dns.isBlank()) return List.of();
		return List.of(dns.trim().split("[,\\s]+", 2));
	}

	private record ConfiguredPort(String id, String name, String interfaceName) {
	}

	private record CommandResult(boolean success, String output, String error) {
	}
}
