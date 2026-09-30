package io.openems.edge.inverter.ligoo;

import static io.openems.common.utils.ReflectionUtils.invokeMethodWithoutArgumentsViaReflection;
import static io.openems.common.utils.ReflectionUtils.setAttributeViaReflection;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;

import org.junit.Test;
import org.osgi.service.component.ComponentContext;

import com.ghgande.j2mod.modbus.procimg.Register;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.ModbusRegisterElement;
import io.openems.edge.bridge.modbus.api.task.AbstractWriteTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.test.DummyComponentContext;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.controller.pvinverter.zeroexport.ControllerPvInverterZeroExportImpl;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.test.DummyElectricityMeter;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.pvinverter.api.SystemCommand;

public class LigooInverterImplTest {

	@Test
	public void testSharedInverterContract() {
		assertTrue(RtsPvInverter.class.isAssignableFrom(LigooInverter.class));
		assertTrue(HybridInverter.class.isAssignableFrom(LigooInverter.class));
		assertTrue(HybridInverterControl.class.isAssignableFrom(LigooInverter.class));
		assertTrue(HybridInverterAlarms.class.isAssignableFrom(LigooInverter.class));
		assertTrue(ManagedSymmetricPvInverter.class.isAssignableFrom(LigooInverter.class));
		assertTrue(ManagedSymmetricPvInverter.class.isAssignableFrom(LigooInverterImpl.class));
		assertTrue(ModbusComponent.class.isAssignableFrom(LigooInverter.class));

		assertFalse(implementsManagedSymmetricEss(LigooInverter.class));
		assertFalse(implementsManagedSymmetricEss(LigooInverterImpl.class));

		var sut = new LigooInverterImpl();
		assertNotNull(sut.channel(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY));
		assertSharedChannelsExist(sut, RtsPvInverter.ChannelId.values());
		assertSharedChannelsExist(sut, HybridInverter.ChannelId.values());
		assertSharedChannelsExist(sut, HybridInverterControl.ChannelId.values());
		assertSharedChannelsExist(sut, HybridInverterAlarms.ChannelId.values());
		assertSharedChannelsExist(sut, ManagedSymmetricPvInverter.ChannelId.values());
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testReactiveManagedSettersExplicitlyReject() throws Exception {
		var sut = activateWriteEnabledSut();
		var protocol = getLigooProtocol(sut);
		var managed = ManagedSymmetricPvInverter.class.cast(sut);

		assertThrows(OpenemsException.class, () -> managed.setMaxReactivePower(1_000));
		assertThrows(OpenemsException.class, () -> managed.setMaxReactivePower(Integer.valueOf(1_000)));
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());
		assertThrows(OpenemsException.class, () -> managed.setModesQ(1));
		assertThrows(OpenemsException.class, () -> managed.setModesQ(Integer.valueOf(1)));
		assertThrows(OpenemsException.class, () -> managed.setReactivePowerLimit(1_000));
		assertThrows(OpenemsException.class, () -> managed.setReactivePowerLimit(Integer.valueOf(1_000)));
		assertThrows(OpenemsException.class, () -> managed.setReactivePowerLimitPercent(50));
		assertThrows(OpenemsException.class, () -> managed.setReactivePowerLimitPercent(Integer.valueOf(50)));
	}

	@Test
	public void testReadTasksGroupContiguousRegistersAndPrioritizeStaticDataLow() {
		var protocol = getProtocol(new LigooInverterImpl());

		assertReadTask(protocol, 0, 3, Priority.LOW);
		assertReadTask(protocol, 22, 1, Priority.HIGH);
		assertReadTask(protocol, 23, 1, Priority.LOW);
		assertReadTask(protocol, 35, 18, Priority.LOW);
		assertReadTask(protocol, 89, 1, Priority.LOW);
		assertReadTask(protocol, 1281, 1, Priority.HIGH);
		assertReadTask(protocol, 2062, 6, Priority.HIGH);
		assertReadTask(protocol, 20526, 2, Priority.HIGH);
		assertReadTask(protocol, 12686, 6, Priority.HIGH);
		assertEquals(26L, protocol.getTaskManager().getTasks().stream()
				.filter(FC3ReadRegistersTask.class::isInstance).count());
	}

	@Test
	public void testDocumentCheckedTelemetry() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(0, 0x0100)
				.withRegisters(1, 0x0000, 0x0102)
				.withRegisters(2062, s32(1234))
				.withRegisters(2064, s32(-2345))
				.withRegisters(2066, s32(3456))
					.withRegister(22, 21)
					.withRegister(23, 80)
					.withRegisters(35, ascii("OFFGRID"))
					.withRegisters(45, ascii("LG1234567890", 8))
					.withRegister(89, 0x1A0A)
					.withRegister(1281, 50)
					.withRegister(20480, 5000)
					.withRegister(20483, 2301)
					.withRegister(20486, 123)
					.withRegisters(20526, s32(4_321))
					.withRegisters(21844, u32LowWordFirst(12_345))
				.withRegisters(12672, u32LowWordFirst(123))
				.withRegisters(12686, u32LowWordFirst(4567))
				.withRegister(12608, 5123)
				.withRegister(12613, 66)
				.withRegister(12615, 5600)
				.withRegister(12616, 4400)
				.withRegister(12622, 5)
				.withRegisters(21859, u32LowWordFirst(2_500))
				.withRegister(20596, 5012)
				.withRegisters(12688, u32LowWordFirst(1000))
				.withRegisters(12690, u32LowWordFirst(2000))
				.withRegister(17152, 3);

		var sut = new LigooInverterImpl();

		new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", bridge)
				.activate(MyConfig.create()
							.setId("inverter0")
							.setModbusId("modbus0")
							.setModbusUnitId(1)
							.build())
					.next(new TestCase("document checked telemetry and first low-priority metadata block"))
					.next(new TestCase("second low-priority metadata block"))
					.next(new TestCase("third low-priority metadata block"))
					.next(new TestCase("fourth low-priority metadata block"))
					.deactivate();

		assertEquals("OFFGRID", sut.channel(LigooInverter.ChannelId.DEVICE_SYSTEM_TYPE).value().get());
		assertEquals("LG1234567890", sut.channel(LigooInverter.ChannelId.SERIAL_NUMBER).value().get());
		assertEquals("1.2.0.0", sut.channel(LigooInverter.ChannelId.FIRMWARE_VERSION).value().get());
		assertEquals("1.0", sut.channel(LigooInverter.ChannelId.HARDWARE_VERSION).value().get());
		assertEquals("26.10", sut.channel(LigooInverter.ChannelId.PROTOCOL_VERSION).value().get());
		assertEquals(Integer.valueOf(8_000), sut.channel(LigooInverter.ChannelId.RATED_POWER).value().get());
		assertEquals(Integer.valueOf(LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue()), sut.getRunMode().get());
		assertEquals(Integer.valueOf(8_000), sut.getMaxActivePower().get());
		assertEquals(Integer.valueOf(8_000), sut.getMaxApparentPower().get());
		assertEquals(Integer.valueOf(4_000), sut.getActivePowerLimit().get());
		assertEquals(Integer.valueOf(4_321), sut.getActivePower().get());
		assertEquals(Integer.valueOf(230_100), sut.getVoltageL1().get());
		assertEquals(Integer.valueOf(12_300), sut.getCurrentL1().get());
		assertEquals(Integer.valueOf(50_000), sut.getFrequency().get());
		assertEquals(Integer.valueOf(1234), sut.getGridActivePowerL1().get());
		assertEquals(Integer.valueOf(-2345), sut.getGridActivePowerL2().get());
		assertEquals(Integer.valueOf(3456), sut.getGridActivePowerL3().get());
		assertEquals(Integer.valueOf(12_345), sut.getDcPower().get());
		assertEquals(Long.valueOf(12_300), sut.getActiveProductionEnergyToday().get());
		assertEquals(Long.valueOf(456_700), sut.getActiveProductionEnergy().get());
		assertEquals(Integer.valueOf(LigooBatteryState.DISCHARGE.getValue()), sut.getBatteryState().get());
		assertEquals(Integer.valueOf(66), sut.getBatterySoc().get());
		assertEquals(Integer.valueOf(512_300), sut.getBatteryVoltage().get());
		assertEquals(Integer.valueOf(560_000), sut.getBatteryChargeVoltageLimit().get());
		assertEquals(Integer.valueOf(440_000), sut.getBatteryDischargeVoltageLimit().get());
		assertEquals(Integer.valueOf(2_500), sut.getGeneratorActivePower().get());
		assertEquals(Integer.valueOf(50_120), sut.getGeneratorFrequency().get());
		assertEquals(Long.valueOf(200_000), sut.getGridImportEnergy().get());
		assertEquals(Long.valueOf(100_000), sut.getGridExportEnergy().get());
		assertEquals(Integer.valueOf(3), sut.channel(LigooInverter.ChannelId.ALARM_ACTIVE_COUNT).value().get());
		assertEquals(Boolean.FALSE, sut.getWriteEnabled().get());
		assertEquals(Long.valueOf(0), sut.getSupportedAlarmMask().get());
		assertEquals(Boolean.FALSE, sut.getAlarmDataValid().get());
	}

	@Test
	public void testActivePowerLimitReadbackClearsPriorValueWhenRunModeUnknown() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(22, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue())
				.withRegister(23, 80)
				.withRegister(1281, 50);
		var sut = new LigooInverterImpl();
		var componentTest = activateSut(sut, bridge);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		processReadCycles(componentTest, "valid active-power-limit readback");
		assertEquals(Integer.valueOf(4_000), sut.getActivePowerLimit().get());

		bridge.withRegister(22, 99);
		setManagedMetadata(sut, 99, 8_000);
		processReadCycles(componentTest, "unknown model clears active-power-limit readback");
		assertEquals(null, sut.getActivePowerLimit().get());
	}

	@Test
	public void testActivePowerLimitReadbackClearsPriorValueWhen100PercentModelExceedsMaximum() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(22, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue())
				.withRegister(23, 80)
				.withRegister(1281, 100);
		var sut = new LigooInverterImpl();
		var componentTest = activateSut(sut, bridge);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		processReadCycles(componentTest, "valid active-power-limit readback");
		assertEquals(Integer.valueOf(8_000), sut.getActivePowerLimit().get());

		bridge.withRegister(1281, 101);
		processReadCycles(componentTest, "100-percent model overrange clears active-power-limit readback");
		assertEquals(null, sut.getActivePowerLimit().get());
	}

	@Test
	public void testActivePowerLimitReadbackClearsPriorValueWhen110PercentModelExceedsMaximum() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(22, LigooRunMode.SINGLE_PHASE_LOW_VOLTAGE_G2.getValue())
				.withRegister(23, 80)
				.withRegister(1281, 110);
		var sut = new LigooInverterImpl();
		var componentTest = activateSut(sut, bridge);

		setManagedMetadata(sut, LigooRunMode.SINGLE_PHASE_LOW_VOLTAGE_G2.getValue(), 8_000);
		processReadCycles(componentTest, "valid active-power-limit readback");
		assertEquals(Integer.valueOf(8_800), sut.getActivePowerLimit().get());

		bridge.withRegister(1281, 111);
		processReadCycles(componentTest, "110-percent model overrange clears active-power-limit readback");
		assertEquals(null, sut.getActivePowerLimit().get());
	}

	@Test
	public void testUnsupportedEnumsClearPriorValues() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(22, 99)
				.withRegister(12622, 99);
		var sut = new LigooInverterImpl();

		var componentTest = new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", bridge)
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.build())
				.next(new TestCase("initial unsupported enums")
						.output(HybridInverter.ChannelId.RUN_MODE, null)
						.output(HybridInverter.ChannelId.BATTERY_STATE, null));
		sut._setRunMode(LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue());
		sut._setBatteryState(LigooBatteryState.DISCHARGE.getValue());
		componentTest.next(new TestCase("unsupported enums after valid")
						.output(HybridInverter.ChannelId.RUN_MODE, null)
						.output(HybridInverter.ChannelId.BATTERY_STATE, null))
				.deactivate();
	}

	@Test
	public void testU32IntegerOverflowClearsPriorValuesWithoutCrashing() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegisters(21844, u32LowWordFirst(0x8000_0000L))
				.withRegisters(21859, u32LowWordFirst(0xFFFF_FFFFL));
		var sut = new LigooInverterImpl();

		var componentTest = new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", bridge)
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.build())
				.next(new TestCase("initial overflow u32 integer channels")
						.output(RtsPvInverter.ChannelId.DC_POWER, null)
						.output(HybridInverter.ChannelId.GENERATOR_ACTIVE_POWER, null));
		sut._setDcPower(12_345);
		sut._setGeneratorActivePower(2_500);
		componentTest.next(new TestCase("overflow u32 integer channels")
						.output(RtsPvInverter.ChannelId.DC_POWER, null)
						.output(HybridInverter.ChannelId.GENERATOR_ACTIVE_POWER, null))
				.deactivate();
	}

	@Test
	public void testUnsupportedEnumsAndUncheckedChannelsStayUnavailable() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(22, 99)
				.withRegister(12622, 99)
				.withRegister(12613, 55);

		new ComponentTest(new LigooInverterImpl())
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", bridge)
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.build())
				.next(new TestCase("unsupported and unchecked stay unavailable")
						.output(HybridInverter.ChannelId.RUN_MODE, null)
						.output(HybridInverter.ChannelId.BATTERY_STATE, null)
						.output(HybridInverter.ChannelId.BATTERY_SOC, 55)
						.output(HybridInverter.ChannelId.OPERATING_STATE, null)
						.output(HybridInverter.ChannelId.GRID_ACTIVE_POWER, null)
						.output(HybridInverter.ChannelId.BATTERY_CURRENT, null)
						.output(HybridInverter.ChannelId.BATTERY_POWER, null)
						.output(HybridInverter.ChannelId.LOAD_ACTIVE_POWER, null)
						.output(HybridInverterControl.ChannelId.CHARGE_CURRENT_LIMIT_READBACK, null)
						.output(HybridInverterControl.ChannelId.DISCHARGE_CURRENT_LIMIT_READBACK, null)
						.output(HybridInverterControl.ChannelId.GRID_CHARGE_ENABLE_READBACK, null)
							.output(ElectricityMeter.ChannelId.ACTIVE_POWER, null)
							.output(ManagedSymmetricPvInverter.ChannelId.MAX_ACTIVE_POWER, null)
							.output(ManagedSymmetricPvInverter.ChannelId.MAX_APPARENT_POWER, null)
						.output(RtsPvInverter.ChannelId.POWER_FACTOR, null)
						.output(HybridInverterAlarms.ChannelId.ALARM_DATA_VALID, false))
				.deactivate();
	}

	@Test
	public void testDisabledWritesRejectAndClearOnlyOwnRawValue() throws Exception {
		var sut = new LigooInverterImpl();
		var componentTest = new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", new DummyModbusBridge("modbus0"))
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.build())
				.next(new TestCase("init write disabled")
						.output(HybridInverterControl.ChannelId.WRITE_ENABLED, false));

		var protocol = getLigooProtocol(sut);
		assertWriteTasks(protocol);
		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);

		sut.setEmsModeRequest(3);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.emsModeRawElement().getNextWriteValueAndReset());

		sut.setChargeCurrentLimitRequest(12_300);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.chargeCurrentLimitRawElement().getNextWriteValueAndReset());

		sut.setDischargeCurrentLimitRequest(23_400);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.dischargeCurrentLimitRawElement().getNextWriteValueAndReset());

		sut.setSystemCommandRequest(SystemCommand.START);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.systemCommandRawElement().getNextWriteValueAndReset());

		sut.setReserveSocRequest(44);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.reserveSocRawElement().getNextWriteValueAndReset());

		sut.setGridChargeEnableRequest(true);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.gridChargeEnableRawElement().getNextWriteValueAndReset());

		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(4_000);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testInvalidWriteValuesRejectAndClearOnlyOwnRawValue() throws Exception {
		var sut = activateWriteEnabledSut();
		var componentTest = new ComponentTest(sut);
		var protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);

		sut.setEmsModeRequest(4);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.emsModeRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		queueAllWrites(sut);
		sut.setChargeCurrentLimitRequest(12_345);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertNull(protocol.chargeCurrentLimitRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		queueAllWrites(sut);
		sut.setDischargeCurrentLimitRequest(-100);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertNull(protocol.dischargeCurrentLimitRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		queueAllWrites(sut);
		sut.setSystemCommandRequest(SystemCommand.UNDEFINED);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertNull(protocol.systemCommandRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		queueAllWrites(sut);
		sut.setReserveSocRequest(101);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertNull(protocol.reserveSocRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		queueAllWrites(sut);
		sut.setGridChargeEnableRequest(null);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertNull(protocol.gridChargeEnableRawElement().getNextWriteValueAndReset());

		queueAllWrites(sut);
		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(-1);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testCanonicalActivePowerLimitPercentConversionAndCaps() throws Exception {
		var sut = activateWriteEnabledSut();
		var componentTest = new ComponentTest(sut);
		var protocol = getLigooProtocol(sut);
		var managed = ManagedSymmetricPvInverter.class.cast(sut);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);

		managed.setActivePowerLimit(0);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		managed.setActivePowerLimit(1);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 5);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		managed.setActivePowerLimit(4_799);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 59);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		managed.setActivePowerLimit(9_000);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 100);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		managed.setActivePowerLimit((Integer) null);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 100);

		setManagedMetadata(sut, LigooRunMode.SINGLE_PHASE_LOW_VOLTAGE_G2.getValue(), 8_000);
		managed.setActivePowerLimit(9_000);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 110);

		managed.setActivePowerLimit((Integer) null);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 110);
	}

	@Test
	public void testZeroExportControllerDispatchesToRegister1281() throws Exception {
		var inverter = new LigooInverterImpl();
		var inverterConfig = MyConfig.create() //
				.setId("inverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(1) //
				.setWriteEnabled(true) //
				.build();
		new ComponentTest(inverter) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(inverterConfig) //
				.next(new TestCase("controller input") //
						.input(ElectricityMeter.ChannelId.ACTIVE_POWER, 4_000));
		setManagedMetadata(inverter, LigooRunMode.SINGLE_PHASE_LOW_VOLTAGE_G2.getValue(), 8_000);

		var meter = new DummyElectricityMeter("meter0");
		meter._setActivePower(2_000);
		meter.getActivePowerChannel().nextProcessImage();
		var componentManager = new DummyComponentManager() //
				.addComponent(inverter) //
				.addComponent(meter);

		var controller = new ControllerPvInverterZeroExportImpl();
		setAttributeViaReflection(controller, "componentManager", componentManager);
		activateZeroExportController(controller, inverterConfig);
		assertEquals(Integer.valueOf(4_000), inverter.getActivePower().get());
		assertEquals(Integer.valueOf(8_800), inverter.getMaxApparentPower().get());
		assertEquals(Integer.valueOf(2_000), meter.getActivePower().get());

		// Grid import is 2 kW. At Kp=0.25 the controller adds 0.5 kW
		// to measured inverter power (4 kW), then LIGOO converts 4.5 kW
		// against 8 kW rated power and floors the register-1281 value to 56%.
		controller.run();
		inverter.getLastCommandResultChannel().nextProcessImage();
		assertEquals(Integer.valueOf(CommandResult.ACCEPTED.getValue()), inverter.getLastCommandResult().get());

		var protocol = getLigooProtocol(inverter);
		assertTask(protocol.activePowerLimitRawElement(), 1281);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 56);
	}

	@Test
	public void testCanonicalActivePowerLimitRejectsMissingUnknownAndOverflowMetadata() throws Exception {
		var sut = activateWriteEnabledSut();
		var componentTest = new ComponentTest(sut);
		var protocol = getLigooProtocol(sut);
		var managed = ManagedSymmetricPvInverter.class.cast(sut);

		managed.setActivePowerLimit(1_000);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());

		setManagedMetadata(sut, 99, 8_000);
		managed.setActivePowerLimit(1_000);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 0);
		managed.setActivePowerLimit(1_000);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testQueuedThenDisabledRejectsActivePowerLimitAndClearsOnly1281() throws Exception {
		var sut = activateWriteEnabledSut();
		var componentTest = new ComponentTest(sut);
		var protocol = getLigooProtocol(sut);
		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_LOW_VOLTAGE.getValue(), 8_000);
		queueAllWrites(sut);
		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(4_000);
		disableWrites(sut);

		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(4_000);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testQueuedThenDisabledRejectsAndClearsOnlyOwnRawValue() throws Exception {
		var sut = activateWriteEnabledSut();
		var componentTest = new ComponentTest(sut);
		var protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		disableWrites(sut);

		sut.setEmsModeRequest(3);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.emsModeRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		sut = activateWriteEnabledSut();
		componentTest = new ComponentTest(sut);
		protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		disableWrites(sut);
		sut.setChargeCurrentLimitRequest(12_300);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertNull(protocol.chargeCurrentLimitRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		sut = activateWriteEnabledSut();
		componentTest = new ComponentTest(sut);
		protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		disableWrites(sut);
		sut.setDischargeCurrentLimitRequest(23_400);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertNull(protocol.dischargeCurrentLimitRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		sut = activateWriteEnabledSut();
		componentTest = new ComponentTest(sut);
		protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		disableWrites(sut);
		sut.setSystemCommandRequest(SystemCommand.START);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertNull(protocol.systemCommandRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		sut = activateWriteEnabledSut();
		componentTest = new ComponentTest(sut);
		protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		disableWrites(sut);
		sut.setReserveSocRequest(44);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertNull(protocol.reserveSocRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		sut = activateWriteEnabledSut();
		componentTest = new ComponentTest(sut);
		protocol = getLigooProtocol(sut);
		queueAllWrites(sut);
		disableWrites(sut);
		sut.setGridChargeEnableRequest(true);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		assertNull(protocol.gridChargeEnableRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testAcceptedWritesQueueExactRawPayloadOnceAndReadBack() throws Exception {
		var bridge = new DummyModbusBridge("modbus0")
				.withRegister(22, LigooRunMode.THREE_PHASE_HIGH_VOLTAGE_T2.getValue())
				.withRegister(23, 80)
				.withRegister(1025, 0)
				.withRegister(13312, 2)
				.withRegister(14595, 0)
				.withRegister(1281, 25)
				.withRegister(14659, 321)
				.withRegister(14660, 432)
				.withRegister(14677, 55);
		var sut = new LigooInverterImpl();
		var componentTest = new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", bridge)
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.setWriteEnabled(true)
						.build())
				.next(new TestCase("init write enabled")
						.output(HybridInverterControl.ChannelId.WRITE_ENABLED, true));

		var protocol = getLigooProtocol(sut);
		assertWriteTasks(protocol);
		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_HIGH_VOLTAGE_T2.getValue(), 8_000);

		sut.setEmsModeRequest(3);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 3);

		sut.setChargeCurrentLimitRequest(12_300);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.chargeCurrentLimitRawElement(), 123);

		sut.setDischargeCurrentLimitRequest(23_400);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.dischargeCurrentLimitRawElement(), 234);

		sut.setSystemCommandRequest(SystemCommand.START);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 1);

		sut.setReserveSocRequest(44);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);

		sut.setGridChargeEnableRequest(true);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 1);

		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_HIGH_VOLTAGE_T2.getValue(), 8_000);
		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(4_000);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 50);

		componentTest.next(new TestCase("readbacks independent of request"));
		assertEquals(Integer.valueOf(2), sut.getEmsModeReadback().get());
		assertEquals(Integer.valueOf(32_100), sut.getChargeCurrentLimitReadback().get());
		assertEquals(Integer.valueOf(43_200), sut.getDischargeCurrentLimitReadback().get());
		assertEquals(Integer.valueOf(SystemCommand.STOP.getValue()), sut.getSystemCommandReadback().get());
		assertEquals(Integer.valueOf(55), sut.getReserveSocReadback().get());
		assertEquals(Boolean.FALSE, sut.getGridChargeEnableReadback().get());
		assertEquals(Integer.valueOf(2_000), sut.getActivePowerLimit().get());

		sut.setEmsModeRequest(3);
		executeWriteTask(protocol.emsModeRawElement(), bridge, 13312);
		sut.setChargeCurrentLimitRequest(12_300);
		executeWriteTask(protocol.chargeCurrentLimitRawElement(), bridge, 14659);
		sut.setDischargeCurrentLimitRequest(23_400);
		executeWriteTask(protocol.dischargeCurrentLimitRawElement(), bridge, 14660);
		sut.setSystemCommandRequest(SystemCommand.START);
		executeWriteTask(protocol.systemCommandRawElement(), bridge, 1025);
		sut.setReserveSocRequest(44);
		executeWriteTask(protocol.reserveSocRawElement(), bridge, 14677);
		sut.setGridChargeEnableRequest(true);
		executeWriteTask(protocol.gridChargeEnableRawElement(), bridge, 14595);
		setManagedMetadata(sut, LigooRunMode.THREE_PHASE_HIGH_VOLTAGE_T2.getValue(), 8_000);
		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(4_000);
		executeWriteTask(protocol.activePowerLimitRawElement(), bridge, 1281);
	}

	@Test
	public void testSystemCommandStopAndGridChargeDisablePayloads() throws Exception {
		var sut = activateWriteEnabledSut();
		var componentTest = new ComponentTest(sut);
		var protocol = getLigooProtocol(sut);

		sut.setSystemCommandRequest(SystemCommand.STOP);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.systemCommandRawElement(), 0);

		sut.setGridChargeEnableRequest(false);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.gridChargeEnableRawElement(), 0);
	}

	private static int[] u32LowWordFirst(long value) {
		return new int[] { (int) (value & 0xFFFF), (int) ((value >>> 16) & 0xFFFF) };
	}

	private static int[] s32(int value) {
		return new int[] { value >> 16, value & 0xFFFF };
	}

	private static int[] ascii(String value) {
		return ascii(value, 10);
	}

	private static int[] ascii(String value, int words) {
		var padded = String.format("%-" + words * 2 + "s", value);
		var registers = new int[words];
		for (var i = 0; i < registers.length; i++) {
			registers[i] = padded.charAt(i * 2) << 8 | padded.charAt(i * 2 + 1);
		}
		return registers;
	}

	private static LigooInverterImpl activateWriteEnabledSut() throws Exception {
		var sut = new LigooInverterImpl();
		new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", new DummyModbusBridge("modbus0"))
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.setWriteEnabled(true)
						.build())
				.next(new TestCase("init write enabled"));
		return sut;
	}

	private static void activateZeroExportController(ControllerPvInverterZeroExportImpl controller,
			MyConfig componentConfig) throws Exception {
		var configType = Class.forName("io.openems.edge.controller.pvinverter.zeroexport.Config");
		var config = Proxy.newProxyInstance(configType.getClassLoader(), new Class<?>[] { configType }, (proxy, method, args) ->
			switch (method.getName()) {
			case "id" -> "ctrlPvInverterZeroExport0";
			case "alias" -> "";
			case "enabled" -> true;
			case "meter_id" -> "meter0";
			case "evn_id" -> "ctrlEvnModbus0";
			case "allowEvnControl", "usePercentage" -> false;
			case "powerLimit" -> 0;
			case "powerLimitPercent" -> 100;
			case "deadbandPercent" -> 1D;
			case "maxRampRatePercent" -> 20D;
			case "gainKp" -> 0.25D;
			case "gainKi" -> 0.005D;
			case "gainKaw" -> 0.1D;
			case "filterAlpha" -> 0.5D;
			case "outputSmoothing" -> 0.8D;
			case "webconsole_configurationFactory_nameHint" -> "Controller [{id}]";
			case "annotationType" -> configType;
			case "toString" -> "Controller.PvInverter.ZeroExport test config";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> throw new UnsupportedOperationException(method.getName());
			});
		var activate = ControllerPvInverterZeroExportImpl.class.getDeclaredMethod("activate", ComponentContext.class,
				configType);
		activate.setAccessible(true);
		activate.invoke(controller, DummyComponentContext.from(componentConfig), config);
	}

	private static ComponentTest activateSut(LigooInverterImpl sut, DummyModbusBridge bridge) throws Exception {
		return new ComponentTest(sut)
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("setModbus", bridge)
				.activate(MyConfig.create()
						.setId("inverter0")
						.setModbusId("modbus0")
						.setModbusUnitId(1)
						.build());
	}

	private static void processReadCycles(ComponentTest componentTest, String description) throws Exception {
		for (var cycle = 0; cycle < 30; cycle++) {
			componentTest.next(new TestCase(description + " " + cycle));
		}
	}

	private static void queueAllWrites(LigooInverterImpl sut) throws Exception {
		sut.setEmsModeRequest(3);
		sut.setChargeCurrentLimitRequest(12_300);
		sut.setDischargeCurrentLimitRequest(23_400);
		sut.setSystemCommandRequest(SystemCommand.START);
		sut.setReserveSocRequest(44);
		sut.setGridChargeEnableRequest(true);
	}

	private static void setManagedMetadata(LigooInverterImpl sut, int runMode, int ratedPower) {
		sut._setRunMode(runMode);
		sut.getRunModeChannel().nextProcessImage();
		sut.channel(LigooInverter.ChannelId.RATED_POWER).setNextValue(ratedPower);
		sut.channel(LigooInverter.ChannelId.RATED_POWER).nextProcessImage();
		sut.getMaxActivePowerChannel().nextProcessImage();
		sut.getMaxApparentPowerChannel().nextProcessImage();
	}

	private static void disableWrites(LigooInverterImpl sut) {
		sut._setWriteEnabled(false);
		sut.getWriteEnabledChannel().nextProcessImage();
	}

	private static ModbusProtocol getProtocol(LigooInverterImpl sut) {
		return invokeMethodWithoutArgumentsViaReflection(AbstractOpenemsModbusComponent.class, sut, "getModbusProtocol");
	}

	private static LigooInverterProtocol getLigooProtocol(LigooInverterImpl sut) {
		return (LigooInverterProtocol) getProtocol(sut);
	}

	private static void assertWriteTasks(LigooInverterProtocol protocol) {
		assertTask(protocol.emsModeRawElement(), 13312);
		assertTask(protocol.chargeCurrentLimitRawElement(), 14659);
		assertTask(protocol.dischargeCurrentLimitRawElement(), 14660);
		assertTask(protocol.systemCommandRawElement(), 1025);
		assertTask(protocol.reserveSocRawElement(), 14677);
		assertTask(protocol.gridChargeEnableRawElement(), 14595);
		assertTask(protocol.activePowerLimitRawElement(), 1281);
		var writeTaskCount = protocol.getTaskManager().getTasks().stream().filter(AbstractWriteTask.class::isInstance).count();
		assertEquals(7L, writeTaskCount);
	}

	private static void assertReadTask(ModbusProtocol protocol, int startAddress, int length, Priority priority) {
		var task = protocol.getTaskManager().getTasks().stream()
				.filter(FC3ReadRegistersTask.class::isInstance)
				.map(FC3ReadRegistersTask.class::cast)
				.filter(t -> t.getStartAddress() == startAddress)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing FC03 read task at " + startAddress));
		assertEquals(length, task.getLength());
		assertEquals(priority, task.getPriority());
	}

	private static void assertTask(ModbusRegisterElement<?, ?> element, int startAddress) {
		var task = element.getModbusTask();
		assertTrue(FC6WriteRegisterTask.class.isInstance(task));
		assertEquals(startAddress, task.getStartAddress());
	}

	private static void executeWriteTask(ModbusRegisterElement<?, ?> element, DummyModbusBridge bridge, int startAddress) {
		assertTask(element, startAddress);
		assertEquals(io.openems.edge.bridge.modbus.api.task.Task.ExecuteState.OK, element.getModbusTask().execute(bridge));
		assertNull(element.getNextWriteValueAndReset());
	}

	private static void assertAccepted(ComponentTest componentTest, LigooInverterImpl sut) throws Exception {
		componentTest.next(new TestCase("accepted command result"));
		assertEquals(sut.getLastCommandRejectionReason().get(), Integer.valueOf(CommandResult.ACCEPTED.getValue()),
				sut.getLastCommandResult().get());
		assertEquals("", sut.getLastCommandRejectionReason().get());
	}

	private static void assertRejected(ComponentTest componentTest, LigooInverterImpl sut, CommandResult result)
			throws Exception {
		componentTest.next(new TestCase("rejected command result"));
		assertEquals(Integer.valueOf(result.getValue()), sut.getLastCommandResult().get());
		assertTrue(sut.getLastCommandRejectionReason().get() instanceof String reason && !reason.isBlank());
	}

	private static void assertRawPayloadOnce(ModbusRegisterElement<?, ?> element, int expected) {
		var registers = element.getNextWriteValueAndReset();
		assertEquals(1, registers.length);
		assertEquals(expected, unsigned(registers[0]));
		assertNull(element.getNextWriteValueAndReset());
	}

	private static int unsigned(Register register) {
		return register.getValue() & 0xFFFF;
	}

	private static void assertSharedChannelsExist(LigooInverterImpl sut, ChannelId[] channelIds) {
		for (var channelId : channelIds) {
			assertNotNull("Missing shared API ChannelId " + channelId.id(), sut.channel(channelId));
		}
	}

	private static boolean implementsManagedSymmetricEss(Class<?> type) {
		if (type == null) {
			return false;
		}
		if ("io.openems.edge.ess.api.ManagedSymmetricEss".equals(type.getName())) {
			return true;
		}
		for (var interfaceType : type.getInterfaces()) {
			if (implementsManagedSymmetricEss(interfaceType)) {
				return true;
			}
		}
		return implementsManagedSymmetricEss(type.getSuperclass());
	}
}
