package io.openems.edge.network.manager.os;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;

public final class NmcliNetworkAdapter implements NetworkSystemAdapter {

	private static final Set<String> MANAGED_INTERFACES = Set.of("eth0", "eth1");
	private static final String CHECKPOINT_HELPER = "/usr/local/sbin/openems-network-checkpoint";
	private final CommandRunner runner;

	public NmcliNetworkAdapter(CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public NetworkProfile ensureProfile(String interfaceName) throws IOException, InterruptedException {
		this.requireManagedInterface(interfaceName);
		var existing = this.findProfile(interfaceName);
		if (existing != null) {
			return existing;
		}
		this.runner.execute(List.of("sudo", "nmcli", "connection", "add", "type", "ethernet", "ifname",
				interfaceName, "con-name", "openems-" + interfaceName));
		var created = this.findProfile(interfaceName);
		if (created == null) {
			throw new IOException("NetworkManager did not create a profile for " + interfaceName);
		}
		return created;
	}

	@Override
	public String createCheckpoint(String interfaceName, int timeoutSeconds) throws IOException, InterruptedException {
		this.requireManagedInterface(interfaceName);
		var output = this.runner.execute(List.of("sudo", CHECKPOINT_HELPER, "create", interfaceName,
				Integer.toString(timeoutSeconds)));
		var firstQuote = output.indexOf('"');
		var secondQuote = firstQuote < 0 ? -1 : output.indexOf('"', firstQuote + 1);
		if (firstQuote < 0 || secondQuote < 0) {
			throw new IOException("NetworkManager returned an invalid checkpoint path");
		}
		return output.substring(firstQuote + 1, secondQuote);
	}

	@Override
	public void apply(NetworkProfile profile, NetworkInterfaceConfig config) throws IOException, InterruptedException {
		this.requireManagedInterface(config.interfaceName());
		var command = new ArrayList<>(List.of("sudo", "nmcli", "connection", "modify", "uuid", profile.uuid()));
		if (config.mode() == Mode.DHCP) {
			var dns = String.join(",", config.dns());
			command.addAll(List.of("ipv4.method", "auto", "ipv4.addresses", "", "ipv4.gateway", "", "ipv4.dns",
					dns, "ipv4.ignore-auto-dns", config.dns().isEmpty() ? "no" : "yes", "connection.autoconnect",
					"yes"));
		} else if (config.mode() == Mode.STATIC) {
			command.addAll(List.of("ipv4.method", "manual", "ipv4.addresses",
					config.ip() + "/" + config.prefixLength(), "ipv4.gateway", nullToEmpty(config.gateway()),
					"ipv4.dns", String.join(",", config.dns()), "connection.autoconnect", "yes"));
		} else {
			throw new IllegalArgumentException("Unsupported network mode: " + config.mode());
		}
		this.runner.execute(command);
	}

	@Override
	public void activate(NetworkProfile profile, String interfaceName) throws IOException, InterruptedException {
		this.requireManagedInterface(interfaceName);
		this.runner.execute(List.of("sudo", "nmcli", "connection", "up", "uuid", profile.uuid(), "ifname",
				interfaceName));
	}

	@Override
	public NetworkVerification verify(NetworkProfile profile, NetworkInterfaceConfig config)
			throws IOException, InterruptedException {
		var output = this.runner.execute(List.of("nmcli", "-g",
				"connection.interface-name,ipv4.method,ipv4.addresses,ipv4.gateway,ipv4.dns,ipv4.ignore-auto-dns",
				"connection", "show",
				"uuid", profile.uuid()));
		var values = output.lines().toList();
		var expectedMethod = config.mode() == Mode.DHCP ? "auto" : "manual";
		var profileVerified = values.size() >= 6 && values.get(0).equals(config.interfaceName())
				&& values.get(1).equals(expectedMethod);
		var configuredAddresses = values.size() > 2 ? values.get(2) : "";
		var configuredGateway = values.size() > 3 ? values.get(3) : "";
		var configuredDns = values.size() > 4 ? values.get(4) : "";
		var ignoreAutoDns = values.size() > 5 ? values.get(5) : "";
		if (config.mode() == Mode.STATIC) {
			profileVerified &= configuredAddresses.contains(config.ip() + "/" + config.prefixLength());
			profileVerified &= configuredGateway.equals(nullToEmpty(config.gateway()));
		} else {
			profileVerified &= configuredAddresses.isBlank() && configuredGateway.isBlank();
		}
		profileVerified &= config.dns().isEmpty() ? configuredDns.isBlank()
				: ignoreAutoDns.equalsIgnoreCase("yes") && config.dns().stream().allMatch(configuredDns::contains);
		var state = this.runner.execute(List.of("nmcli", "-g",
				"GENERAL.STATE,GENERAL.CON-UUID,WIRED-PROPERTIES.CARRIER", "device", "show",
				config.interfaceName()));
		var stateValues = state.lines().toList();
		var connected = !stateValues.isEmpty() && stateValues.get(0).toLowerCase().contains("connected");
		var activeProfileUuid = stateValues.size() > 1 ? stateValues.get(1) : "";
		var linkAvailable = stateValues.size() > 2 && stateValues.get(2).equalsIgnoreCase("on");
		var runtimeVerified = linkAvailable && connected && activeProfileUuid.equals(profile.uuid());
		return new NetworkVerification(profileVerified, runtimeVerified, linkAvailable);
	}

	@Override
	public void commitCheckpoint(String checkpointId) throws IOException, InterruptedException {
		this.runCheckpointMethod("CheckpointDestroy", checkpointId);
	}

	@Override
	public void rollbackCheckpoint(String checkpointId) throws IOException, InterruptedException {
		this.runCheckpointMethod("CheckpointRollback", checkpointId);
	}

	@Override
	public ObservedNetworkState observe(String interfaceName) throws IOException, InterruptedException {
		this.requireManagedInterface(interfaceName);
		var deviceState = this.runner.execute(List.of("nmcli", "-g", "GENERAL.STATE", "device", "show", interfaceName));
		var carrier = this.runner.execute(
				List.of("nmcli", "-g", "WIRED-PROPERTIES.CARRIER", "device", "show", interfaceName));
		var addresses = nonBlankLines(this.runner
				.execute(List.of("nmcli", "-g", "IP4.ADDRESS", "device", "show", interfaceName)));
		var gateway = emptyToNull(this.runner
				.execute(List.of("nmcli", "-g", "IP4.GATEWAY", "device", "show", interfaceName)));
		var dns = nonBlankLines(
				this.runner.execute(List.of("nmcli", "-g", "IP4.DNS", "device", "show", interfaceName)));
		var activeProfile = emptyToNull(this.runner
				.execute(List.of("nmcli", "-g", "GENERAL.CONNECTION", "device", "show", interfaceName)));
		if ("--".equals(activeProfile)) {
			activeProfile = null;
		}
		return new ObservedNetworkState(normalizeDeviceState(deviceState), carrier.equalsIgnoreCase("on"), addresses, gateway, dns,
				activeProfile);
	}

	private static String normalizeDeviceState(String deviceState) {
		var separator = deviceState.indexOf(' ');
		var numericState = separator < 0 ? deviceState.trim() : deviceState.substring(0, separator).trim();
		return switch (numericState) {
		case "10" -> "UNMANAGED";
		case "20" -> "UNAVAILABLE";
		case "30" -> "DISCONNECTED";
		case "40" -> "PREPARE";
		case "50" -> "CONFIG";
		case "60" -> "NEED_AUTH";
		case "70" -> "IP_CONFIG";
		case "80" -> "IP_CHECK";
		case "90" -> "SECONDARIES";
		case "100" -> "ACTIVATED";
		case "110" -> "DEACTIVATING";
		case "120" -> "FAILED";
		default -> "UNKNOWN";
		};
	}

	private NetworkProfile findProfile(String interfaceName) throws IOException, InterruptedException {
		var output = this.runner.execute(List.of("nmcli", "-t", "-f", "UUID", "connection", "show"));
		for (var line : output.lines().toList()) {
			var uuid = line.trim();
			if (uuid.isBlank()) {
				continue;
			}
			var profileName = this.runner
					.execute(List.of("nmcli", "-g", "connection.id", "connection", "show", "uuid", uuid)).trim();
			if (!profileName.equals("openems-" + interfaceName)) {
				continue;
			}
			var profileInterface = this.runner.execute(List.of("nmcli", "-g", "connection.interface-name",
					"connection", "show", "uuid", uuid)).trim();
			if (profileInterface.equals(interfaceName)) {
				return new NetworkProfile(uuid, profileInterface);
			}
		}
		return null;
	}

	private void requireManagedInterface(String interfaceName) {
		if (!MANAGED_INTERFACES.contains(interfaceName)) {
			throw new IllegalArgumentException("Unsupported network interface: " + interfaceName);
		}
	}

	private void runCheckpointMethod(String method, String checkpointId) throws IOException, InterruptedException {
		var action = method.equals("CheckpointDestroy") ? "commit" : "rollback";
		this.runner.execute(List.of("sudo", CHECKPOINT_HELPER, action, checkpointId));
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static List<String> nonBlankLines(String value) {
		return value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
	}
}
