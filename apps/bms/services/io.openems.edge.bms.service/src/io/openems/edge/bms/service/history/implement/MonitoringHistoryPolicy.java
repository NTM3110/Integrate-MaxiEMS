package io.openems.edge.bms.service.history.implement;

import java.time.Instant;

/** Validated persistence cadence and retention policy. */
public record MonitoringHistoryPolicy(int rawCadenceSeconds, int rawRetentionDays,
		int fiveMinuteRetentionDays, int hourlyRetentionDays) {

	static final int MINIMUM_RAW_CADENCE_SECONDS = 15;

	public MonitoringHistoryPolicy {
		if (rawCadenceSeconds < MINIMUM_RAW_CADENCE_SECONDS) {
			throw new IllegalArgumentException("Raw cadence must be at least 15 seconds");
		}
		if (rawRetentionDays <= 0 || rawRetentionDays > fiveMinuteRetentionDays
				|| fiveMinuteRetentionDays > hourlyRetentionDays) {
			throw new IllegalArgumentException("Retention must satisfy 0 < raw <= 5-minute <= hourly");
		}
	}

	public static MonitoringHistoryPolicy defaults() {
		return new MonitoringHistoryPolicy(30, 30, 365, 1_825);
	}

	public static Instant bucketStart(Instant value, long bucketSeconds) {
		if (bucketSeconds <= 0) {
			throw new IllegalArgumentException("Bucket size must be positive");
		}
		return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond(), bucketSeconds) * bucketSeconds);
	}
}
