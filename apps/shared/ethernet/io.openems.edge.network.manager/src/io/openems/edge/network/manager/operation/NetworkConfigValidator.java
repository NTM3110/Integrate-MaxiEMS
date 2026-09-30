package io.openems.edge.network.manager.operation;

import java.util.Set;

import io.openems.edge.network.manager.Mode;

public final class NetworkConfigValidator {

	private static final Set<String> MANAGED_INTERFACES = Set.of("eth0", "eth1");

	public NetworkInterfaceConfig validate(NetworkInterfaceConfig config) {
		if (!MANAGED_INTERFACES.contains(config.interfaceName())) {
			throw invalid(NetworkErrorCode.INVALID_INTERFACE, "Unsupported network interface");
		}
		if (config.mode() == Mode.NONE) {
			throw invalid(NetworkErrorCode.INVALID_IP_ADDRESS, "Mode NONE is not configurable");
		}
		if (config.dns().size() > 2 || config.dns().stream().anyMatch(address -> !isIpv4(address))) {
			throw invalid(NetworkErrorCode.INVALID_DNS, "DNS must contain at most two IPv4 addresses");
		}
		if (config.mode() == Mode.DHCP) {
			if (config.ip() != null || config.prefixLength() != null || config.gateway() != null) {
				throw invalid(NetworkErrorCode.INVALID_IP_ADDRESS, "DHCP must not include static address fields");
			}
			return config;
		}
		if (!isIpv4(config.ip())) {
			throw invalid(NetworkErrorCode.INVALID_IP_ADDRESS, "Invalid static IPv4 address");
		}
		if (config.prefixLength() == null || config.prefixLength() < 0 || config.prefixLength() > 32) {
			throw invalid(NetworkErrorCode.INVALID_PREFIX_LENGTH, "IPv4 prefix length must be between 0 and 32");
		}
		if (config.gateway() != null && !isIpv4(config.gateway())) {
			throw invalid(NetworkErrorCode.INVALID_GATEWAY, "Invalid IPv4 gateway");
		}
		return config;
	}

	private static boolean isIpv4(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		var parts = value.split("\\.", -1);
		if (parts.length != 4) {
			return false;
		}
		for (var part : parts) {
			if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
				return false;
			}
			var octet = Integer.parseInt(part);
			if (octet > 255 || part.length() > 1 && part.startsWith("0")) {
				return false;
			}
		}
		return true;
	}

	private static NetworkValidationException invalid(NetworkErrorCode code, String message) {
		return new NetworkValidationException(code, message);
	}
}
