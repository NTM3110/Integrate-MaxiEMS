package io.openems.edge.bms.service.discharge.implement;

import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DischargeCurrentDirectionTest {

	@Test
	public void mapsBothDeviceSignsToPositiveDomainDischarge() {
		assertEquals(12.5, DischargeCurrentDirection.POSITIVE.toDomainCurrent(12.5), 0.000001);
		assertEquals(12.5, DischargeCurrentDirection.NEGATIVE.toDomainCurrent(-12.5), 0.000001);
		assertEquals(-4.0, DischargeCurrentDirection.POSITIVE.toDomainCurrent(-4.0), 0.000001);
		assertEquals(-4.0, DischargeCurrentDirection.NEGATIVE.toDomainCurrent(4.0), 0.000001);
	}

	@Test
	public void eventDirectionDoesNotFollowLaterConfigChanges() {
		var eventDirection = DischargeCurrentDirection.NEGATIVE;
		var laterConfigDirection = DischargeCurrentDirection.POSITIVE;

		assertEquals(8.0, eventDirection.toDomainCurrent(-8.0), 0.000001);
		assertEquals(-8.0, laterConfigDirection.toDomainCurrent(-8.0), 0.000001);
		assertEquals(DischargeCurrentDirection.NEGATIVE, eventDirection);
	}
}
