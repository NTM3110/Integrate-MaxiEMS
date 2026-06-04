package io.openems.edge.bridge.edmi;

import java.util.Arrays;
import java.util.List;

public record ProfileIngestionSettings(//
		short survey, //
		int intervalMinutes, //
		int firstReadDelayMinutes, //
		int retryMinutes, //
		int finalizeAfterMinutes, //
		int maxRecords, //
		List<String> timestampAliases, //
		List<String> recordStatusAliases, //
		List<String> importWhAliases, //
		List<String> exportWhAliases, //
		List<String> importVahAliases, //
		List<String> exportVahAliases //
) {

	public static ProfileIngestionSettings from(Config config) {
		return new ProfileIngestionSettings(//
				parseSurvey(config.profileSurvey()), //
				positive(config.profileIntervalMinutes(), 30), //
				positive(config.profileFirstReadDelayMinutes(), 5), //
				positive(config.profileRetryMinutes(), 5), //
				positive(config.profileFinalizeAfterMinutes(), 30), //
				positive(config.profileMaxRecords(), 5), //
				aliases(config.profileTimestampAliases()), //
				aliases(config.profileRecordStatusAliases()), //
				aliases(config.profileImportWhAliases()), //
				aliases(config.profileExportWhAliases()), //
				aliases(config.profileImportVahAliases()), //
				aliases(config.profileExportVahAliases()));
	}

	public long intervalMillis() {
		return this.intervalMinutes * 60_000L;
	}

	public long firstReadDelayMillis() {
		return this.firstReadDelayMinutes * 60_000L;
	}

	public long retryMillis() {
		return this.retryMinutes * 60_000L;
	}

	public long finalizeAfterMillis() {
		return this.finalizeAfterMinutes * 60_000L;
	}

	private static int positive(int value, int fallback) {
		return value > 0 ? value : fallback;
	}

	private static List<String> aliases(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split("\\|")) //
				.map(String::trim) //
				.filter(value -> !value.isBlank()) //
				.toList();
	}

	private static short parseSurvey(String raw) {
		if (raw == null || raw.isBlank()) {
			return (short) 0x0325;
		}
		var normalized = raw.trim();
		try {
			if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
				return (short) Integer.parseInt(normalized.substring(2), 16);
			}
			return (short) Integer.parseInt(normalized);
		} catch (NumberFormatException e) {
			return (short) 0x0325;
		}
	}
}
