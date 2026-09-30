package io.openems.edge.logger.huawei;

import org.junit.Test;

import io.openems.common.channel.AccessMode;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;

public class HuaweiLoggerImplTest {

	private static final int ACTIVE_POWER_W = 5_432;
	private static final long TOTAL_PRODUCTION_WH = 123_456_700L;
	private static final float DAILY_PRODUCTION_KWH = 12.3F;

	@Test
	public void mapsAggregateRegistersAndCreatesReadOnlyInverterChannels() throws Exception {
		var modbus = new DummyModbusBridge("modbus0") //
				.withRegisters(40525, toRegisters(ACTIVE_POWER_W)) //
				.withRegisters(40560, toRegisters((int) (TOTAL_PRODUCTION_WH / 100)),
						toRegisters((int) (DAILY_PRODUCTION_KWH * 100)));
		var sut = new HuaweiLoggerImpl();

		var test = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", modbus) //
				.activate(MyConfig.create().withInverterAddresses("1, 2").build());

		test.next(new TestCase(), 30) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, ACTIVE_POWER_W) //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL, TOTAL_PRODUCTION_WH) //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY, DAILY_PRODUCTION_KWH));

		var inverterActivePower = sut.channels().stream() //
				.filter(channel -> channel.channelId().name().equals("_PV_INVERTER1/ACTIVE_POWER")) //
				.findFirst() //
				.orElseThrow(() -> new AssertionError("Configured inverter channel was not created"));
		if (inverterActivePower.channelDoc().getAccessMode() != AccessMode.READ_ONLY) {
			throw new AssertionError("Inverter channel must be read-only");
		}
		if (sut.channels().stream() //
				.noneMatch(channel -> channel.channelId().name().equals("_PV_INVERTER2/ACTIVE_POWER"))) {
			throw new AssertionError("Configured inverter channel was not created");
		}
	}

	private static int[] toRegisters(int value) {
		return new int[] { //
				(value >>> 16) & 0xFFFF, //
				value & 0xFFFF //
		};
	}
}
