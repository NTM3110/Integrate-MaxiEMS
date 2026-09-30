package io.openems.edge.bms.service.history.implement;

import io.openems.edge.bms.service.history.implement.persistence.MonitoringHistoryStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.Instant;

import org.junit.Test;

import io.openems.edge.bms.service.history.implement.persistence.MonitoringHistoryStore.PersistResult;

public class MonitoringHistoryPolicyTest {

	@Test
	public void defaultsRespectPhysicalCadenceAndRetentionOrder() {
		var policy = MonitoringHistoryPolicy.defaults();
		assertEquals(30, policy.rawCadenceSeconds());
		assertEquals(30, policy.rawRetentionDays());
		assertEquals(365, policy.fiveMinuteRetentionDays());
		assertEquals(1_825, policy.hourlyRetentionDays());
	}

	@Test
	public void rejectsOversamplingAndNonMonotonicRetention() {
		assertThrows(IllegalArgumentException.class, () -> new MonitoringHistoryPolicy(14, 30, 365, 1_825));
		assertThrows(IllegalArgumentException.class, () -> new MonitoringHistoryPolicy(30, 366, 365, 1_825));
		assertThrows(IllegalArgumentException.class, () -> new MonitoringHistoryPolicy(30, 30, 1_826, 1_825));
	}

	@Test
	public void floorsBucketsAgainstUtcEpoch() {
		var value = Instant.parse("2026-09-07T03:17:49Z");
		assertEquals(Instant.parse("2026-09-07T03:15:00Z"), MonitoringHistoryPolicy.bucketStart(value, 300));
		assertEquals(Instant.parse("2026-09-07T03:00:00Z"), MonitoringHistoryPolicy.bucketStart(value, 3_600));
	}

	@Test
	public void unknownStringFrameRemainsRetryable() {
		assertEquals(false, MonitoringHistoryWriter.shouldRemember(PersistResult.STRING_NOT_FOUND));
		assertEquals(true, MonitoringHistoryWriter.shouldRemember(PersistResult.PERSISTED));
		assertEquals(true, MonitoringHistoryWriter.shouldRemember(PersistResult.CADENCE_SKIPPED));
	}
}
