package io.openems.edge.pvinverter.deye.hybrid;

import static io.openems.common.utils.ReflectionUtils.invokeMethodWithoutArgumentsViaReflection;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ghgande.j2mod.modbus.procimg.Register;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.test.DummyConfigurationAdmin.DummyConfiguration;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.element.ModbusRegisterElement;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;

public class PvInverterDeyeHybridImplTest {

	@Test
	public void testContractsAreSeparated() {
		assertTrue(HybridInverter.class.isAssignableFrom(PvInverterDeyeHybrid.class));
		assertTrue(HybridInverterControl.class.isAssignableFrom(PvInverterDeyeHybrid.class));
		assertTrue(HybridInverterAlarms.class.isAssignableFrom(PvInverterDeyeHybrid.class));
	}

	@Test
	public void testDocumentedBatteryAndEmsReadbacks() throws Exception {
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegister(77, 500) //
				.withRegister(108, 25) //
				.withRegister(109, 30) //
				.withRegister(117, 15) //
				.withRegister(130, 1) //
				.withRegister(141, 3) //
				.withRegister(218, 40) //
				.withRegister(219, 50) //
				.withRegister(220, 0) //
				.withRegister(221, 0) //
				.withRegister(222, 1) //
				.withRegisters(516, 123, 0) //
				.withRegisters(518, 45, 0) //
				.withRegisters(520, 0, 0) //
				.withRegisters(522, 100, 0) //
				.withRegisters(524, 20, 0) //
				.withRegister(587, 5200) //
				.withRegister(588, 55) //
				.withRegister(589, 0) //
				.withRegister(590, -300) //
				.withRegister(591, -100) //
				.withRegister(1100, 7) //
				.withRegister(1101, 9) //
				.withRegister(1109, -250);

		var sut = new PvInverterDeyeHybridImpl();
		new ComponentTest(sut) //
				.addReference("cm", cmWithExistingEss()) //
				.addReference("setModbus", bridge) //
				.activate(baseConfig(false)) //
				.next(new TestCase("documented Deye EMS registers"));

		assertEquals(Integer.valueOf(5000), sut.getActivePowerLimitReadback().get());
		assertEquals(Integer.valueOf(25_000), sut.getChargeCurrentLimitReadback().get());
		assertEquals(Integer.valueOf(30_000), sut.getDischargeCurrentLimitReadback().get());
		assertEquals(Integer.valueOf(15), sut.getReserveSocReadback().get());
		assertEquals(Boolean.TRUE, sut.getGridChargeEnableReadback().get());
		assertEquals(Integer.valueOf(3), sut.getEmsModeReadback().get());
		assertEquals(Integer.valueOf(40_000), sut.getBmsMaxChargeCurrent().get());
		assertEquals(Integer.valueOf(50_000), sut.getBmsMaxDischargeCurrent().get());
		assertEquals(Integer.valueOf(1), sut.getBmsCommunicationStatus().get());
		assertEquals(Long.valueOf(12_300L), sut.getBatteryChargeEnergy().get());
		assertEquals(Long.valueOf(4500L), sut.getBatteryDischargeEnergy().get());
		assertEquals(Long.valueOf(10_000L), sut.getGridImportEnergy().get());
		assertEquals(Long.valueOf(2000L), sut.getGridExportEnergy().get());
		assertEquals(Integer.valueOf(520_000), sut.getBatteryVoltage().get());
		assertEquals(Integer.valueOf(55), sut.getBatterySoc().get());
		assertEquals(Integer.valueOf(-3000), sut.getBatteryPower().get());
		assertEquals(Integer.valueOf(-1000), sut.getBatteryCurrent().get());
		assertEquals(Integer.valueOf(7), sut.getRemoteEnableReadback().get());
		assertEquals(Integer.valueOf(9), sut.getHeartbeatReadback().get());
		assertEquals(Integer.valueOf(-250), sut.getBatteryPowerReferenceReadback().get());
		assertEquals(Boolean.FALSE, sut.getWriteEnabled().get());
	}

	@Test
	public void testWritesAreDisabledByDefault() throws Exception {
		var sut = new PvInverterDeyeHybridImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", cmWithExistingEss()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(baseConfig(false)) //
				.next(new TestCase("write gate is closed"));
		var protocol = protocol(sut);

		sut.setBatteryPowerReferenceRequest(-250);
		componentTest.next(new TestCase("setpoint rejected"));
		assertEquals(Integer.valueOf(CommandResult.REJECTED_DISABLED.getValue()),
				sut.getLastCommandResult().get());
		assertNull(protocol.batteryPowerReferenceRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testDoesNotCreateEssConfiguration() throws Exception {
		var cm = new DummyConfigurationAdmin();
		var sut = new PvInverterDeyeHybridImpl();
		new ComponentTest(sut) //
				.addReference("cm", cm) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(baseConfig(false)) //
				.next(new TestCase("PV activation does not manage ESS configuration"));

		assertNull(cm.getFactoryConfiguration("Ess.Deye.Hybrid", "ess0"));
	}

	@Test
	public void testAcceptedWritesUseDocumentedRegistersAndUnits() throws Exception {
		var sut = new PvInverterDeyeHybridImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", cmWithExistingEss()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(baseConfig(true)) //
				.next(new TestCase("write gate is open"));
		var protocol = protocol(sut);

		sut.setBatteryPowerReferenceRequest(-250);
		assertRaw(protocol.batteryPowerReferenceRawElement(), 0xFF06);
		sut.setRemoteEnableRequest(7);
		assertRaw(protocol.remoteEnableRawElement(), 7);
		sut.setEmsModeRequest(3);
		assertRaw(protocol.emsModeRawElement(), 3);
		sut.setChargeCurrentLimitRequest(25_000);
		assertRaw(protocol.chargeCurrentLimitRawElement(), 25);
		sut.setDischargeCurrentLimitRequest(30_000);
		assertRaw(protocol.dischargeCurrentLimitRawElement(), 30);
		sut.setReserveSocRequest(15);
		assertRaw(protocol.reserveSocRawElement(), 15);
		sut.setGridChargeEnableRequest(true);
		assertRaw(protocol.gridChargeEnableRawElement(), 1);
		sut.setActivePowerLimitRequest(5000);
		assertRaw(protocol.activePowerLimitRawElement(), 500);

		componentTest.next(new TestCase("commands accepted"));
		assertEquals(Integer.valueOf(CommandResult.ACCEPTED.getValue()), sut.getLastCommandResult().get());
	}

	private static MyConfig baseConfig(boolean writeEnabled) {
		return MyConfig.create() //
				.setId("pvInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(1) //
				.setMaxActivePower(10_000) //
				.setWriteEnabled(writeEnabled) //
				.build();
	}

	private static DummyConfigurationAdmin cmWithExistingEss() {
		return new DummyConfigurationAdmin().addConfiguration("Ess.Deye.Hybrid", new DummyConfiguration() //
				.addProperty("id", "ess0") //
				.addProperty("enabled", true) //
				.addProperty("inverter.id", "pvInverter0") //
				.addProperty("deye.autoManagedBy", "pvInverter0"));
	}

	private static DeyeHybridProtocol protocol(PvInverterDeyeHybridImpl sut) {
		return (DeyeHybridProtocol) invokeMethodWithoutArgumentsViaReflection(AbstractOpenemsModbusComponent.class,
				sut, "getModbusProtocol");
	}

	private static void assertRaw(ModbusRegisterElement<?, ?> element, int expected) {
		var registers = element.getNextWriteValueAndReset();
		assertEquals(1, registers.length);
		assertEquals(expected, unsigned(registers[0]));
		assertNull(element.getNextWriteValueAndReset());
	}

	private static int unsigned(Register register) {
		return register.getValue() & 0xFFFF;
	}
}
