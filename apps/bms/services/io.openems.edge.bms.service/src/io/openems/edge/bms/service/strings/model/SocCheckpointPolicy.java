package io.openems.edge.bms.service.strings.model;

/** Per-string persistence policy for baseline-aware SoC estimator state. */
public enum SocCheckpointPolicy {
	DISABLED,
	PERSIST_DATABASE;

	public static SocCheckpointPolicy parse(String value) {
		if (value == null || value.isBlank()) return DISABLED;
		try {
			return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException error) {
			throw new IllegalArgumentException("Unknown SoC checkpoint policy [" + value + "]", error);
		}
	}
}
