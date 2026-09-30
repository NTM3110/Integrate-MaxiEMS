package io.openems.edge.modem.manager;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class ModemNetworkDiscovery {

	record ModemIpAddress(String interfaceName, String ipAddress) {
	}

	private static final Pattern IP_ADDR = Pattern.compile("^\\d+:\\s+([^\\s]+)\\s+inet\\s+([0-9.]+)(?:/|\\s).*");
	private static final Pattern AT_PORT = Pattern.compile("/dev/tty(?:USB|ACM)\\d+");

	private ModemNetworkDiscovery() {
	}

	static List<ModemIpAddress> parseGlobalAddresses(String output) {
		return output.lines()
				.map(IP_ADDR::matcher)
				.filter(matcher -> matcher.matches() && isModemInterface(matcher.group(1)))
				.map(matcher -> new ModemIpAddress(matcher.group(1), matcher.group(2)))
				.toList();
	}

	static String firstModemIp(String output) {
		return parseGlobalAddresses(output).stream()
				.map(ModemIpAddress::ipAddress)
				.findFirst()
				.orElse("");
	}

	static List<String> sortAtPortCandidates(List<String> ports) {
		return ports.stream()
				.filter(ModemNetworkDiscovery::isAtPortCandidate)
				.distinct()
				.sorted(Comparator.comparingInt(ModemNetworkDiscovery::portRank).thenComparing(Comparator.naturalOrder()))
				.toList();
	}

	static boolean isAtPortCandidate(String port) {
		return port != null && AT_PORT.matcher(port).matches();
	}

	private static boolean isModemInterface(String name) {
		return name.startsWith("usb") || name.startsWith("wwan") || name.startsWith("ppp");
	}

	private static int portRank(String port) {
		if ("/dev/ttyUSB2".equals(port)) {
			return 0;
		}
		if (port.startsWith("/dev/ttyUSB")) {
			return 1;
		}
		return 2;
	}
}
