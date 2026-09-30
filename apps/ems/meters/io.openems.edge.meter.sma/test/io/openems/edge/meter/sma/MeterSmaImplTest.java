package io.openems.edge.meter.sma;

import static io.openems.common.types.MeterType.PRODUCTION;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.api.ElectricityMeter;

public class MeterSmaImplTest {

	private static int[] emptyRegisters(int count) {
		return new int[count];
	}

	private static ComponentTest prepareTest(boolean invert) throws Exception {
		return new ComponentTest(new MeterSmaImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(30581, 0, 50, 0, 100) // consumption, production [Wh]
						.withRegisters(31253, //
								0, 23000, // voltage L1: 230.00 V
								0, 23100, // voltage L2: 231.00 V
								0, 23200, // voltage L3: 232.00 V
								0, 1000, // active power L1
								0, 1100, // active power L2
								0, 900) // active power L3
						.withRegisters(31265, emptyRegisters(6)) // unused 31265..31270
						.withRegisters(31271, //
								0, 100, // reactive power L1
								0, 200, // reactive power L2
								0, 300, // reactive power L3
								0, 600) // reactive power total
						.withRegisters(31433, //
								0, 98, // power factor: 0.98
								0, 1000, // current L1: 1000 mA
								0, 2000, // current L2: 2000 mA
								0, 3000) // current L3: 3000 mA
						.withRegisters(31441, //
								0, 1010, // apparent power L1
								0, 1110, // apparent power L2
								0, 910) // apparent power L3
						.withRegisters(31447, 0, 5000) // frequency: 50.00 Hz
						.withRegisters(31449, emptyRegisters(6)) // unused 31449..31454
						.withRegisters(31455, 0, 3030) // apparent power total
						.withRegisters(31457, emptyRegisters(84)) // unused 31457..31540
						.withRegisters(31541, 0xFFFF, 0xFFFF)) // unavailable average voltage
				.activate(MyConfig.create() //
						.setId("meter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(10) //
						.setType(PRODUCTION) //
						.setInvert(invert) //
						.build());
	}

	@Test
	public void testReadAcOutput() throws Exception {
		prepareTest(false) //
				.next(new TestCase(), 30) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, 1000) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, 1100) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, 900) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER, 600) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, 100) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, 200) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, 300) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER, 3030) //
						.output(ElectricityMeter.ChannelId.CURRENT, 6000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L1, 1000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L2, 2000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L3, 3000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 230_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L2, 231_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L3, 232_000) //
						.output(ElectricityMeter.ChannelId.FREQUENCY, 50_000) //
						.output(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, 50L) //
						.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, 100L) //
						.output(MeterSma.ChannelId.POWER_FACTOR, 0.98F)) //
				.deactivate();
	}

	@Test
	public void testInvertPower() throws Exception {
		prepareTest(true) //
				.next(new TestCase(), 30) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, -1000) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER, -600) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER, -3030)) //
				.deactivate();
	}
}
