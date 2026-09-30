package io.openems.edge.logger.sma;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.test.DummyElectricityMeter;

public class 	SmaLoggerImplTest {

	private static final long TOTAL_YIELD_WH = 123_456_789L;
	private static final long DAILY_YIELD_WH = 12_345L;
	private static final int LOGGER_ACTIVE_POWER_W = 5_432;
	private static final int METER_ACTIVE_POWER_W = -876;

	@Test
	public void testKeepsLoggerActivePowerAndForwardsGridConnectionPointActivePower() throws Exception {
		var modbus = new DummyModbusBridge("modbus0") //
				.withRegisters(30513, toRegisters(TOTAL_YIELD_WH)) //
				.withRegisters(30517, toRegisters(DAILY_YIELD_WH)) //
				.withRegisters(30775, toRegisters(LOGGER_ACTIVE_POWER_W)) //
				.withRegisters(31249, toRegisters(METER_ACTIVE_POWER_W));
		var sut = new SmaLoggerImpl();
		var meter = new DummyElectricityMeter("meter0");

		var test = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("meter", meter) //
				.addReference("setModbus", modbus) //
				.activate(MyConfig.create() //
						.setId("smartLogger0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(2) //
						.build());

		// The first cycle registers the Modbus tasks; the second evaluates the values.
		test.next(new TestCase()) //
				.next(new TestCase() //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL, TOTAL_YIELD_WH) //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY, DAILY_YIELD_WH / 1000F) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, LOGGER_ACTIVE_POWER_W));

		assertSame(sut.getEnergyProductionTodayChannel(), sut.getDailyYieldChannel());
		assertSame(sut.getActivePowerChannel(), sut.getPowerChannel());
		assertEquals(Integer.valueOf(METER_ACTIVE_POWER_W), meter.getActivePowerChannel().getNextValue().get());
	}

	private static int[] toRegisters(long value) {
		return new int[] { //
				(int) (value >>> 48) & 0xFFFF, //
				(int) (value >>> 32) & 0xFFFF, //
				(int) (value >>> 16) & 0xFFFF, //
				(int) value & 0xFFFF //
		};
	}

	private static int[] toRegisters(int value) {
		return new int[] { //
				(value >>> 16) & 0xFFFF, //
				value & 0xFFFF //
		};
	}
}
