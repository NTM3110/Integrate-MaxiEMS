package io.openems.edge.modem.manager;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

final class ModemCommandBuilder {

	private static final String PPP_UPDATE_HELPER = "/usr/local/sbin/openems-update-modem-ppp";
	private static final int AT_COMMAND_TIMEOUT_SECONDS = 3;

	private ModemCommandBuilder() {
	}

	static List<String> statusCommands() {
		return List.of("ATE0", "AT+CSQ", "AT+CGSN", "AT+COPS?", "AT+CGMM", "AT+CPIN?", "AT+QNETDEVCTL?");
	}

	static List<String> ec200ConfigureAndStartCommands(String apn, String username, String password) {
		var authMode = isBlank(username) && isBlank(password) ? 0 : 1;
		return List.of("ATE0", "AT+QICSGP=1,1," + ModemAtParser.toAtQuotedString(apn) + ","
				+ ModemAtParser.toAtQuotedString(username) + "," + ModemAtParser.toAtQuotedString(password) + ","
				+ authMode,
				"AT+QNETDEVCTL=1,1,1");
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	static CommandSpec buildAtBatchCommand(String port, List<String> atCommands, boolean failOnError) {
		if (!ModemNetworkDiscovery.isAtPortCandidate(port)) {
			throw new IllegalArgumentException("Unsupported AT port");
		}
		var payload = new StringBuilder();
		for (var command : atCommands) {
			payload.append(command).append("\n");
		}
		var recordCommand = failOnError ? "" : "printf '%s\\n' \"$command\" >> \"$out\"; ";
		var onTimeout = failOnError ? "cat \"$out\"; exit 8; " : "break; ";
		var onCommandError = failOnError
				? "if [ \"$commandError\" -eq 1 ]; then cat \"$out\"; exit 9; fi; "
				: "";
		return new CommandSpec("test -e " + port + " && (stty -F " + port
				+ " raw -echo 2>/dev/null || exit 7; out=$(mktemp); trap 'rm -f \"$out\"' EXIT; "
				+ "exec 3<&0; exec 4<> " + port + " || exit 7; "
				+ "for drainAttempt in {1..20}; do IFS= read -r -t 0.01 _discard <&4 || break; done; "
				+ "while IFS= read -r command <&3; do " + recordCommand
				+ "printf '%s\\r' \"$command\" >&4; terminal=0; commandError=0; "
				+ "endSecond=$((SECONDS + " + AT_COMMAND_TIMEOUT_SECONDS + ")); "
				+ "while (( SECONDS < endSecond )); do "
				+ "if IFS= read -r -t 0.2 responseLine <&4; then normalizedLine=${responseLine%$'\\r'}; "
				+ "if [ \"$normalizedLine\" != \"$command\" ]; then "
				+ "printf '%s\\n' \"$normalizedLine\" >> \"$out\"; fi; "
				+ "case \"$normalizedLine\" in OK) terminal=1; break ;; "
				+ "ERROR|'+CME ERROR:'*|'+CMS ERROR:'*) terminal=1; commandError=1; break ;; esac; fi; done; "
				+ "if [ \"$terminal\" -ne 1 ]; then " + onTimeout + "fi; " + onCommandError
				+ "done; cat \"$out\"; exit 0)", payload.toString());
	}

	static CommandSpec buildPppConfigCommand(String apn, String username, String password) {
		var content = encodePppValue(apn) + "\n" + encodePppValue(username) + "\n" + encodePppValue(password) + "\n";
		return new CommandSpec("sudo " + PPP_UPDATE_HELPER, content);
	}

	private static String encodePppValue(String value) {
		value = value == null ? "" : value;
		if (value.chars().anyMatch(ch -> ch < 0x20 || ch == '\n' || ch == '\r')) {
			throw new IllegalArgumentException("PPP value contains unsupported characters");
		}
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
