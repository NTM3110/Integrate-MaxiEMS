package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.meter.api.ElectricityMeter;

public class RtsPvInverterChannelIdFactoryTest {

	@Test
	public void testChannelMetadataAndStableIdentity() {
		var voltage = RtsPvInverterChannelIdFactory.dcVoltage(12);
		assertEquals("DC12_VOLTAGE", voltage.name());
		assertEquals("Dc12Voltage", voltage.id());
		assertEquals(OpenemsType.INTEGER, voltage.doc().getType());
		assertEquals(Unit.MILLIVOLT, voltage.doc().getUnit());
		assertEquals(PersistencePriority.HIGH, voltage.doc().getPersistencePriority());
		assertSame(RtsPvInverter.ChannelId.DC12_VOLTAGE, voltage);
		assertSame(voltage, RtsPvInverterChannelIdFactory.dcVoltage(12));

		var current = RtsPvInverterChannelIdFactory.dcCurrent(12);
		assertEquals("Dc12Current", current.id());
		assertEquals(Unit.MILLIAMPERE, current.doc().getUnit());
		assertSame(RtsPvInverter.ChannelId.DC12_CURRENT, current);

		var power = RtsPvInverterChannelIdFactory.dcPower(12);
		assertEquals("Dc12Power", power.id());
		assertEquals(Unit.WATT, power.doc().getUnit());

		var dynamicVoltage = RtsPvInverterChannelIdFactory.dcVoltage(21);
		assertEquals("Dc21Voltage", dynamicVoltage.id());
		assertEquals(Unit.MILLIVOLT, dynamicVoltage.doc().getUnit());
		assertSame(dynamicVoltage, RtsPvInverterChannelIdFactory.dcVoltage(21));
	}

	@Test
	public void testDcInputVoltageAndCurrentChannelsArePredefined() {
		assertEquals(24, RtsPvInverter.PREDEFINED_DC_INPUT_COUNT);
		assertEquals(5 + 2 * RtsPvInverter.PREDEFINED_DC_INPUT_COUNT, RtsPvInverter.ChannelId.values().length);

		for (var inputIndex = 1; inputIndex <= RtsPvInverter.PREDEFINED_DC_INPUT_COUNT; inputIndex++) {
			var voltage = RtsPvInverter.ChannelId.valueOf("DC" + inputIndex + "_VOLTAGE");
			assertEquals(Unit.MILLIVOLT, voltage.doc().getUnit());
			assertEquals(OpenemsType.INTEGER, voltage.doc().getType());
			assertSame(voltage, RtsPvInverterChannelIdFactory.dcVoltage(inputIndex));

			var current = RtsPvInverter.ChannelId.valueOf("DC" + inputIndex + "_CURRENT");
			assertEquals(Unit.MILLIAMPERE, current.doc().getUnit());
			assertEquals(OpenemsType.INTEGER, current.doc().getType());
			assertSame(current, RtsPvInverterChannelIdFactory.dcCurrent(inputIndex));
		}
	}

	@Test
	public void testInputChannelOrderAndValidation() {
		var channels = RtsPvInverterChannelIdFactory.dcInputChannels(2);
		assertEquals(6, channels.size());
		assertEquals("Dc1Voltage", channels.get(0).id());
		assertEquals("Dc1Current", channels.get(1).id());
		assertEquals("Dc1Power", channels.get(2).id());
		assertEquals("Dc2Voltage", channels.get(3).id());

		assertThrows(IllegalArgumentException.class, () -> RtsPvInverterChannelIdFactory.dcPower(0));
		assertThrows(IllegalArgumentException.class, () -> RtsPvInverterChannelIdFactory.dcInputChannels(-1));
		assertThrows(UnsupportedOperationException.class, () -> channels.add(channels.get(0)));
	}

	@Test
	public void testFixedChannelMetadata() {
		assertEquals(Unit.MILLIVOLT, RtsPvInverter.ChannelId.DC_VOLTAGE.doc().getUnit());
		assertEquals(Unit.MILLIAMPERE, RtsPvInverter.ChannelId.DC_CURRENT.doc().getUnit());
		assertEquals(Unit.WATT, RtsPvInverter.ChannelId.DC_POWER.doc().getUnit());
		assertEquals(Unit.THOUSANDTH, RtsPvInverter.ChannelId.POWER_FACTOR.doc().getUnit());
		assertEquals(OpenemsType.LONG,
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY.doc().getType());
		assertEquals(Unit.CUMULATED_WATT_HOURS,
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY.doc().getUnit());
		assertFalse(java.util.Arrays.stream(RtsPvInverter.ChannelId.values())
				.anyMatch(channelId -> channelId.id().equals("ActiveProductionEnergyToday")));
	}
}
