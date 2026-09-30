package io.openems.edge.pvinverter.solis.ongrid;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.utils.ReflectionUtils;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverterAlarmId;
import io.openems.edge.pvinverter.api.RtsPvInverterAlarms;
import io.openems.edge.pvinverter.api.RtsPvInverterChannelIdFactory;

public class PvInverterSolisOnGridImplTest {

	private static final String COMPONENT_ID = "pvInverter0";
	private static final String MODBUS_ID = "modbus0";

	@Test
	public void mapsMonitoringRegistersAndDerivedChannels() throws Exception {
		var bridge = new DummyModbusBridge(MODBUS_ID) //
				.withInputRegisters(2999, 0x1234, 1, 2, 1, 1) // Two DC inputs
				.withInputRegisters(3004, //
						0, 12_000, // Active power
						0, 14_000, // Total DC power
						0, 1_234, // Total production energy in kWh
						0, 0, 0, 0, // Reserved
						123) // Production today in 0.1 kWh
				.withInputRegisters(3021, 6_000, 100, 6_500, 80) // DC voltage/current pairs
				.withInputRegisters(3033, //
						2_300, 2_310, 2_320, // Phase voltages
						100, 110, 120, // Phase currents
						0, 0, // Reserved
						455, 5_000, 3, // Temperature, frequency, inverter status
						0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // Reserved
						0, 6_000, // Reactive power
						0, 15_000) // Apparent power
				.withInputRegisters(3066, bits(0), 0, 0, 0, 0, 1) // Fault words and working status
				.withInputRegister(35000, 0x1020);

		var test = createTest(bridge, 100_000);
		test.next(new TestCase(), 20);
		test.next(new TestCase() //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER, 12_000) //
				.output(RtsPvInverter.ChannelId.DC_INPUT_COUNT, 2) //
				.output(RtsPvInverter.ChannelId.DC_VOLTAGE, 625_000) // Average of the two strings
				.output(RtsPvInverter.ChannelId.DC_CURRENT, 18_000) // Sum of the two strings
				.output(RtsPvInverter.ChannelId.DC_POWER, 14_000) //
				.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, 1_234_000L) //
				.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY, 12_300L) //
				.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 230_000) //
				.output(ElectricityMeter.ChannelId.VOLTAGE, 231_000) //
				.output(ElectricityMeter.ChannelId.CURRENT, 33_000) //
				.output(ElectricityMeter.ChannelId.FREQUENCY, 50_000) //
				.output(ElectricityMeter.ChannelId.REACTIVE_POWER, 6_000) //
				.output(ElectricityMeter.ChannelId.APPARENT_POWER, 15_000) //
				.output(RtsPvInverter.ChannelId.POWER_FACTOR, 800) //
				.output(RtsPvInverterChannelIdFactory.dcVoltage(1), 600_000) //
				.output(RtsPvInverterChannelIdFactory.dcCurrent(1), 10_000) //
				.output(RtsPvInverterChannelIdFactory.dcPower(1), 6_000) //
				.output(RtsPvInverterChannelIdFactory.dcPower(2), 5_200) //
				.output(RtsPvInverterAlarms.ChannelId.ALARM_DATA_VALID, true) //
				.output(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE.channelId().orElseThrow(), true) //
				.output(RtsPvInverterAlarms.ChannelId.UNSUPPORTED_DEVICE, false) //
				.output(RtsPvInverterAlarms.ChannelId.DEVICE_OFFLINE, null));
	}

	@Test
	public void reportsUnsupportedDcInputCountWithoutGuessingRegisters() throws Exception {
		var bridge = new DummyModbusBridge(MODBUS_ID) //
				.withInputRegisters(2999, 0x1234, 1, 2, 1, 4) // Five DC inputs are not documented
				.withInputRegisters(3004, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) //
				.withInputRegisters(3033, new int[26]) //
				.withInputRegisters(3066, 0, 0, 0, 0, 0, 0) //
				.withInputRegister(35000, 0x1020);

		var test = createTest(bridge, 100_000);
		test.next(new TestCase(), 20);
		test.next(new TestCase() //
				.output(RtsPvInverter.ChannelId.DC_INPUT_COUNT, 5) //
				.output(RtsPvInverterAlarms.ChannelId.ALARM_DATA_VALID, true) //
				.output(RtsPvInverterAlarms.ChannelId.INVALID_DATA, false) // Zero power is valid at night
				.output(RtsPvInverterAlarms.ChannelId.UNSUPPORTED_DEVICE, true));
	}

	@Test
	public void scalesActiveAndReactivePowerCommandsForIndependentFc6Writes() throws Exception {
		var sut = new PvInverterSolisOnGridImpl();
		createTest(sut, new DummyModbusBridge(MODBUS_ID), 100_000);
		ModbusProtocol protocol = ReflectionUtils.getValueViaReflection(sut, "protocol");
		var writeAddresses = protocol.getTaskManager().getTasks().stream() //
				.filter(FC6WriteRegisterTask.class::isInstance) //
				.map(task -> task.getStartAddress()) //
				.toList();
		assertEquals(List.of(3069, 3070, 3080, 3082), writeAddresses);

		sut.setActivePowerLimit(12_340);
		new SetPvLimitHandler(sut, ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT).run();
		IntegerWriteChannel remoteControl = sut.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL);
		IntegerWriteChannel activePowerRaw = sut.channel(PvInverterSolisOnGrid.ChannelId.P_LIMIT_RAW);
		assertEquals(Optional.of(0xAA), remoteControl.getNextWriteValue());
		assertEquals(Optional.of(1_234), activePowerRaw.getNextWriteValue());

		sut.setReactivePowerLimit(-5_670);
		new SetReactivePowerLimitHandler(sut, ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT).run();
		IntegerWriteChannel remoteControlQ = sut.channel(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q);
		assertEquals(Optional.of(0xA1), remoteControlQ.getNextWriteValue());
		assertEquals(Optional.of(-567), sut.getReactivePowerLimitChannel().getNextWriteValue());
	}

	private static ComponentTest createTest(DummyModbusBridge bridge, int maxActivePower) throws Exception {
		return createTest(new PvInverterSolisOnGridImpl(), bridge, maxActivePower);
	}

	private static ComponentTest createTest(PvInverterSolisOnGridImpl sut, DummyModbusBridge bridge,
			int maxActivePower) throws Exception {
		return new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", bridge) //
				.activate(MyConfig.create() //
						.setId(COMPONENT_ID) //
						.setModbusId(MODBUS_ID) //
						.setModbusUnitId(1) //
						.setMaxActivePower(maxActivePower) //
						.build());
	}

	private static int bits(int... bits) {
		var result = 0;
		for (var bit : bits) {
			result |= 1 << bit;
		}
		return result;
	}
}
