package io.openems.edge.modem.manager;

import java.util.regex.Pattern;

final class ModemAtParser {

	record AtStatus(String model, String simStatus, String qnetdevctl, int signalPercent, int signalDbm,
			String operatorName, String connectionType, String imei, boolean hasStatusEvidence) {

		boolean isEc200() {
			return this.model.toUpperCase().startsWith("EC200");
		}

		boolean isEcmActive() {
			return "1,1,1,1".equals(this.qnetdevctl);
		}

		boolean isSimReady() {
			return "READY".equalsIgnoreCase(this.simStatus);
		}
	}

	private static final Pattern CSQ = Pattern.compile("\\+CSQ:\\s*(\\d+),");
	private static final Pattern COPS_OPERATOR = Pattern.compile("\\+COPS:\\s*\\d+,\\d+,\"([^\"]+)\"");
	private static final Pattern COPS_ACT = Pattern.compile("\\+COPS:\\s*\\d+,\\d+,\"[^\"]*\",(\\d+)");
	private static final Pattern IMEI = Pattern.compile("\\b\\d{15}\\b");
	private static final Pattern CPIN = Pattern.compile("\\+CPIN:\\s*([^\\r\\n]+)");
	private static final Pattern CPIN_SECTION = Pattern.compile("(?i)AT\\+CPIN\\?\\r?\\n([\\s\\S]*?)(?=AT\\+|\\z)");
	private static final Pattern CME_ERROR = Pattern.compile("\\+CME ERROR:\\s*(\\d+)");
	private static final Pattern QNETDEVCTL = Pattern.compile("\\+QNETDEVCTL:\\s*(\\d+,\\d+,\\d+,\\d+)");
	private static final Pattern EC200_MODEL = Pattern.compile("(?im)^\\s*(EC200[A-Z0-9._-]*)\\s*$");

	private ModemAtParser() {
	}

	static AtStatus parse(String output) {
		var model = parseModel(output);
		var simStatus = parseSimStatus(output);
		var imei = parseImei(output);
		var hasStatusEvidence = !model.isEmpty() || !simStatus.isEmpty() || !imei.isEmpty()
				|| CSQ.matcher(nullToEmpty(output)).find();
		return new AtStatus(model, simStatus, parseQnetdevctl(output), parseSignalPercent(output), parseSignalDbm(output),
				parseOperator(output), parseConnectionType(output), imei, hasStatusEvidence);
	}

	static boolean hasStatusEvidence(String output) {
		return parse(output).hasStatusEvidence();
	}

	static boolean hasStatusEvidence(AtStatus status) {
		return status.hasStatusEvidence();
	}

	static String toAtQuotedString(String value) {
		if (value == null) {
			return "\"\"";
		}
		if (value.chars().anyMatch(ch -> ch < 0x20 || ch == '\"' || ch == '\\')) {
			throw new IllegalArgumentException("AT value contains unsupported characters");
		}
		return "\"" + value + "\"";
	}

	private static int parseSignalPercent(String output) {
		var matcher = CSQ.matcher(nullToEmpty(output));
		if (!matcher.find()) {
			return 0;
		}
		var rssi = Integer.parseInt(matcher.group(1));
		return rssi == 99 ? 0 : Math.min(100, Math.max(0, Math.round(rssi * 100F / 31F)));
	}

	private static int parseSignalDbm(String output) {
		var matcher = CSQ.matcher(nullToEmpty(output));
		if (!matcher.find()) {
			return 0;
		}
		var rssi = Integer.parseInt(matcher.group(1));
		return rssi == 99 ? 0 : -113 + 2 * rssi;
	}

	private static String parseOperator(String output) {
		var matcher = COPS_OPERATOR.matcher(nullToEmpty(output));
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String parseConnectionType(String output) {
		var matcher = COPS_ACT.matcher(nullToEmpty(output));
		if (!matcher.find()) {
			return "";
		}
		return switch (Integer.parseInt(matcher.group(1))) {
		case 0, 1 -> "GSM";
		case 3 -> "EDGE";
		case 2, 4, 5, 6 -> "3G";
		case 7 -> "LTE";
		case 13 -> "LTE-M";
		case 14 -> "NB-IoT";
		default -> "Unknown";
		};
	}

	private static String parseModel(String output) {
		var normalizedOutput = nullToEmpty(output);
		var sectionMatcher = Pattern.compile("AT\\+CGMM\\r?\\n([\\s\\S]*?)(?=AT\\+|$)").matcher(normalizedOutput);
		if (sectionMatcher.find()) {
			for (var line : sectionMatcher.group(1).split("\\n")) {
				var trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("OK") && !trimmed.startsWith("AT+")) {
					return trimmed;
				}
			}
		}
		var ec200Matcher = EC200_MODEL.matcher(normalizedOutput);
		return ec200Matcher.find() ? ec200Matcher.group(1) : "";
	}

	private static String parseImei(String output) {
		var matcher = IMEI.matcher(nullToEmpty(output));
		return matcher.find() ? matcher.group() : "";
	}

	private static String parseSimStatus(String output) {
		var normalizedOutput = nullToEmpty(output);
		var matcher = CPIN.matcher(normalizedOutput);
		if (matcher.find()) {
			return matcher.group(1).trim();
		}
		var sectionMatcher = CPIN_SECTION.matcher(normalizedOutput);
		if (!sectionMatcher.find()) {
			return "";
		}
		var errorMatcher = CME_ERROR.matcher(sectionMatcher.group(1));
		if (!errorMatcher.find()) {
			return "";
		}
		return "10".equals(errorMatcher.group(1)) ? "NOT INSERTED" : "CME ERROR: " + errorMatcher.group(1);
	}

	private static String parseQnetdevctl(String output) {
		var matcher = QNETDEVCTL.matcher(nullToEmpty(output));
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
