package io.openems.edge.bms.controllers.monitoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;

public class BmsMonitoringPublisherTest {

	@Test
	public void missingCalculatorPublishesImmediateStaleEvidenceAndRecoveryRetainsMeasurementIdentity() {
		var publisher = new BmsMonitoringPublisher();
		long now = 1_000_000L;
		var source = BmsMonitoringEvaluatorTest.snapshot(now, 253.0, 0.1, 1_800, 95.0);
		var context = ActiveCalibrationContext.none();
		var policy = BmsMonitoringPolicy.defaults();
		var direction = BmsCurrentDirectionMapping.NEGATIVE;
		assertNull(publisher.evaluate("str1", null, null, context, policy, direction, now));
		var first = publisher.evaluate("str1", source, null, context, policy, direction, now);
		var outage = publisher.evaluate("str1", null, null, context, policy, direction, now + 1);
		assertTrue(outage.stale());
		assertTrue(outage.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(ModeQuality.STALE, outage.operatingMode().quality());
		assertSame(source, outage.calculationSnapshot());
		assertEquals(first.capturedAt(), outage.capturedAt());
		assertEquals(first.publicationRevision() + 1, outage.publicationRevision());
		assertSame(outage, publisher.evaluate("str1", null, null, context, policy, direction, now + 2));
		var recovered = publisher.evaluate("str1", source, null, context, policy, direction, now + 3);
		assertFalse(recovered.stale());
		assertFalse(recovered.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(outage.publicationRevision() + 1, recovered.publicationRevision());
		assertEquals(first.sourceSequence(), recovered.sourceSequence());
	}

	@Test
	public void unchangedPollsReusePublicationAndStaleTransitionRetainsSourceIdentity() {
		var publisher = new BmsMonitoringPublisher();
		long now = 1_000_000L;
		var source = BmsMonitoringEvaluatorTest.snapshot(now, 253.0, 0.1, 1_800, 95.0);
		var context = ActiveCalibrationContext.none();
		var policy = BmsMonitoringPolicy.defaults();
		var direction = BmsCurrentDirectionMapping.NEGATIVE;
		var first = publisher.evaluate("str1", source, null, context, policy, direction, now);
		assertFalse(first.referenceAvailable());
		assertEquals(OperatingMode.UNKNOWN, first.operatingMode().mode());
		assertSame(first, publisher.evaluate("str1", source, null, context, policy, direction, now + 10_000));
		var stale = publisher.evaluate("str1", source, null, context, policy, direction, now + 61_000);
		assertTrue(stale.stale());
		assertEquals(first.publicationRevision() + 1, stale.publicationRevision());
		assertEquals(first.publicationGeneration(), stale.publicationGeneration());
		assertEquals(first.sourceGeneration(), stale.sourceGeneration());
		assertEquals(first.sourceSequence(), stale.sourceSequence());
		assertEquals(Instant.ofEpochMilli(now), stale.capturedAt());
		assertEquals(ModeQuality.STALE, stale.operatingMode().quality());
		assertSame(stale, publisher.evaluate("str1", source, null, context, policy, direction, now + 62_000));
		var recoveredSource = BmsMonitoringEvaluatorTest.snapshot(now + 63_000, 253.0, 0.1, 1_800, 95.0);
		var recovered = publisher.evaluate("str1", recoveredSource, null, context, policy, direction, now + 63_000);
		assertFalse(recovered.stale());
		assertFalse(recovered.activeAlarms().contains(Alarm.SENSOR_MODULE_FAILURE));
		assertEquals(stale.publicationRevision() + 1, recovered.publicationRevision());
	}

	@Test
	public void referenceArrivalReplacementAndRemovalReevaluateSameMeasurement() {
		var publisher = new BmsMonitoringPublisher();
		long now = 1_000_000L;
		var source = BmsMonitoringEvaluatorTest.snapshot(now, 253.0, 0.1, 1_800, 95.0);
		var context = ActiveCalibrationContext.none();
		var policy = BmsMonitoringPolicy.defaults();
		var direction = BmsCurrentDirectionMapping.NEGATIVE;
		var first = publisher.evaluate("str1", source, null, context, policy, direction, now);
		var baseline = BmsMonitoringEvaluatorTest.baseline();
		var arrived = publisher.evaluate("str1", source, baseline, context, policy, direction, now + 1);
		assertTrue(arrived.referenceAvailable());
		assertEquals(first.publicationRevision() + 1, arrived.publicationRevision());
		assertEquals(OperatingMode.UNKNOWN, arrived.operatingMode().mode());
		assertSame(arrived, publisher.evaluate("str1", source, baseline, context, policy, direction, now + 2));
		var replacement = BmsMonitoringEvaluatorTest.baseline();
		var replaced = publisher.evaluate("str1", source, replacement, context, policy, direction, now + 3);
		assertEquals(arrived.publicationRevision() + 1, replaced.publicationRevision());
		var removed = publisher.evaluate("str1", source, null, context, policy, direction, now + 4);
		assertFalse(removed.referenceAvailable());
		assertEquals(replaced.publicationRevision() + 1, removed.publicationRevision());
		assertSame(source, removed.calculationSnapshot());
	}

	@Test
	public void activeContextAndPolicyChangesArePublishedWithoutANewAcquisition() {
		var publisher = new BmsMonitoringPublisher();
		long now = 1_000_000L;
		var source = BmsMonitoringEvaluatorTest.snapshot(now, 253.0, 50.0, 1_800, 95.0);
		var policy = BmsMonitoringPolicy.defaults();
		var direction = BmsCurrentDirectionMapping.NEGATIVE;
		var first = publisher.evaluate("str1", source, null, ActiveCalibrationContext.none(), policy, direction, now);
		var context = new ActiveCalibrationContext(42L, 2L, 3L, "INITIAL_BASELINE", "RECHARGE",
				"RECOVERY_CHARGE_TO_VBOOST", 40.0);
		var active = publisher.evaluate("str1", source, null, context, policy, direction, now + 1);
		assertEquals(OperatingMode.CHARGING, active.operatingMode().mode());
		assertEquals(40.0, active.thresholds().chargeOvercurrentA(), 0.0);
		assertEquals(first.publicationRevision() + 1, active.publicationRevision());
		var updated = new ActiveCalibrationContext(42L, 2L, 3L, "INITIAL_BASELINE", "RECHARGE",
				"RECOVERY_CHARGE_TO_VBOOST", 60.0);
		var changed = publisher.evaluate("str1", source, null, updated, policy, direction, now + 2);
		assertEquals(60.0, changed.thresholds().chargeOvercurrentA(), 0.0);
		assertEquals(active.publicationRevision() + 1, changed.publicationRevision());
		publisher.reset();
		var restarted = publisher.evaluate("str1", source, null, updated, policy, direction, now + 3);
		assertNotEquals(changed.publicationGeneration(), restarted.publicationGeneration());
		assertEquals(1L, restarted.publicationRevision());
	}
}
