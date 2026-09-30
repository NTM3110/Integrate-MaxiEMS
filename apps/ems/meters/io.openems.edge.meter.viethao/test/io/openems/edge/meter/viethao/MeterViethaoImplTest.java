package io.openems.edge.meter.viethao;

import static io.openems.common.types.MeterType.PRODUCTION;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.api.ElectricityMeter;

public class MeterViethaoImplTest {

	private static ComponentTest prepareTest(boolean invert) throws Exception {
		return new ComponentTest(new MeterViethaoImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0, // registers 0..17 from the Viethao EMS protocol
								2300, 2310, 2320, // voltage: 230.0, 231.0, 232.0 V
								1000, 2000, 3000, // current: 10, 20, 30 A
								100, 200, 300, 600, // active power
								10, 20, 30, 60, // reactive power
								110, 220, 330, 660)) // apparent power
				.activate(MyConfig.create() //
						.setId("meter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(10) //
						.setType(PRODUCTION) //
						.setInvert(invert) //
						.build());
	}

	@Test
	public void testReadViethaoRegisters() throws Exception {
		prepareTest(false) //
				.next(new TestCase(), 30) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, 60_000) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, 10_000) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, 20_000) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, 30_000) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER, 6_000) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, 1_000) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, 2_000) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, 3_000) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER, 66_000) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER_L1, 11_000) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER_L2, 22_000) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER_L3, 33_000) //
						.output(ElectricityMeter.ChannelId.CURRENT, 60_000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L1, 10_000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L2, 20_000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L3, 30_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 230_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L2, 231_000) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L3, 232_000)) //
				.deactivate();
	}

	@Test
	public void testInvertPower() throws Exception {
		prepareTest(true) //
				.next(new TestCase(), 30) //
				.next(new TestCase() //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, -60_000) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER, -6_000) //
						.output(ElectricityMeter.ChannelId.APPARENT_POWER, -66_000)) //
				.deactivate();
	}
}
