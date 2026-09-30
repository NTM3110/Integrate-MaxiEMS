package io.openems.edge.logger.sungrow;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;

public class SungrowLoggerImplTest {

	private static final int ACTIVE_POWER_W = 5_432;
	private static final float DAILY_PRODUCTION_KWH = 12.3F;
	private static final long TOTAL_PRODUCTION_WH = 123_456_700L;

	@Test
	public void mapsProductionAndActivePowerToStandardChannels() throws Exception {
		var modbus = new DummyModbusBridge("modbus0") //
				.withInputRegisters(8069, registers());
		var sut = new SungrowLoggerImpl();

		var test = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", modbus) //
				.activate(MyConfig.create().build());

		// The Modbus bridge registers and executes the tasks across its cycle queue.
		test.next(new TestCase(), 30) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, ACTIVE_POWER_W) //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY, DAILY_PRODUCTION_KWH) //
						.output(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL, TOTAL_PRODUCTION_WH));
	}

	private static int[] registers() {
		var result = new int[14];
		System.arraycopy(toRegisters((long) ACTIVE_POWER_W), 0, result, 0, 4);
		result[4] = 123;
		System.arraycopy(toRegisters(TOTAL_PRODUCTION_WH / 100), 0, result, 10, 4);
		return result;
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
