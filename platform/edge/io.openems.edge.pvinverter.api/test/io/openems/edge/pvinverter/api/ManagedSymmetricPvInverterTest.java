package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import io.openems.common.channel.Unit;
import io.openems.edge.pvinverter.test.DummyManagedSymmetricPvInverter;

public class ManagedSymmetricPvInverterTest {

	@Test
	@SuppressWarnings("deprecation")
	public void testLegacyMaxReactivePowerDelegatesToReactivePowerLimit() throws Exception {
		var sut = new DummyManagedSymmetricPvInverter("inverter0");

		sut.setMaxReactivePower(1_234);

		assertEquals(Integer.valueOf(1_234), sut.getReactivePowerLimitChannel().getNextWriteValue().orElseThrow());
		assertFalse(sut.getActivePowerLimitChannel().getNextWriteValue().isPresent());
	}

	@Test
	public void testReactivePowerLimitUsesVarUnit() {
		assertEquals(Unit.VOLT_AMPERE_REACTIVE,
				ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT.doc().getUnit());
	}
}
