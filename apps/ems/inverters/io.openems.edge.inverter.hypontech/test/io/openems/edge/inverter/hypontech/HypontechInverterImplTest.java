package io.openems.edge.inverter.hypontech;

import static io.openems.common.utils.ReflectionUtils.getValueViaReflection;
import static io.openems.common.utils.ReflectionUtils.invokeMethodWithoutArgumentsViaReflection;
import static io.openems.common.utils.ReflectionUtils.setAttributeViaReflection;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;

import com.ghgande.j2mod.modbus.procimg.Register;
import com.google.gson.JsonPrimitive;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.test.TimeLeapClock;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.ModbusRegisterElement;
import io.openems.edge.bridge.modbus.api.task.AbstractWriteTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.test.DummyComponentContext;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.controller.pvinverter.zeroexport.ControllerPvInverterZeroExportImpl;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.test.DummyElectricityMeter;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy.Snapshot;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.test.DummyTimedata;

public class HypontechInverterImplTest {

	@Test
	public void testDefaultConstructorOwnsDedicatedVirtualThreadExecutor() throws Exception {
		var sut = new HypontechInverterImpl();
		ExecutorService executor = getValueViaReflection(sut, "dailyProductionEnergyExecutor");
		assertTrue(executor.submit(() -> Thread.currentThread().isVirtual()).get());

		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
				.deactivate();

		assertTrue(executor.isShutdown());
	}

	@Test
	public void testRestoresDailyProductionEnergyFromTimedata() throws Exception {
		var clock = new TimeLeapClock(Instant.parse("2026-08-04T05:00:00Z"), ZoneId.of("UTC"));
		var timestamp = ZonedDateTime.now(clock).minusHours(1);
		var timedata = new DummyTimedata("timedata0");
		timedata.add(timestamp, new ChannelAddress("inverter0",
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY.id()), new JsonPrimitive(120_000L));
		timedata.add(timestamp, new ChannelAddress("inverter0",
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY.id()), new JsonPrimitive(5_000L));
		var sut = new HypontechInverterImpl(clock, null, Runnable::run);
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("setTimedata", timedata) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
				.next(new TestCase("initialize Timedata restore"));

		sut._setActiveProductionEnergy(120_500L);
		sut._setActivePower(1_000);
		sut.getActiveProductionEnergyChannel().nextProcessImage();
		sut.getActivePowerChannel().nextProcessImage();

		sut.handleEvent(new Event(EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, Map.of()));
		sut.handleEvent(new Event(EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, Map.of()));
		sut.getActiveProductionEnergyTodayChannel().nextProcessImage();

		assertEquals(Long.valueOf(5_500L), sut.getActiveProductionEnergyToday().get());
	}

	@Test
	public void testCalculatesDailyProductionEnergyFromRestoredTotalBaseline() throws Exception {
		var timestamp = Instant.parse("2026-08-04T03:00:00Z");
		var snapshot = new Snapshot(LocalDate.of(2026, 8, 4), null, 10_000L, true, 10_000L,
				Instant.parse("2026-08-04T00:00:00Z"), Instant.parse("2026-08-04T00:00:00Z"), 0, 0L, 0L, true);
		var sut = new HypontechInverterImpl(Clock.fixed(timestamp, ZoneId.of("UTC")), snapshot);
		var timedataQueryCount = new AtomicInteger();
		var timedata = (Timedata) Proxy.newProxyInstance(Timedata.class.getClassLoader(), new Class<?>[] { Timedata.class },
				(proxy, method, args) -> {
					if ("queryHistoricData".equals(method.getName())) {
						timedataQueryCount.incrementAndGet();
						return new TreeMap<>();
					}
					return switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "Counting Timedata test proxy";
					default -> null;
					};
				});
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("setTimedata", timedata) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
				.next(new TestCase("initialize restored daily-energy state"));

		sut._setActiveProductionEnergy(10_750L);
		sut._setActivePower(2_000);
		sut.getActiveProductionEnergyChannel().nextProcessImage();
		sut.getActivePowerChannel().nextProcessImage();

		sut.handleEvent(new Event(EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, Map.of()));
		sut.getActiveProductionEnergyTodayChannel().nextProcessImage();

		assertEquals(Long.valueOf(750L), sut.getActiveProductionEnergyToday().get());
		assertEquals(0, timedataQueryCount.get());
	}

	@Test
	public void testSharedInverterContract() {
		assertTrue(RtsPvInverter.class.isAssignableFrom(HypontechInverter.class));
		assertTrue(HybridInverter.class.isAssignableFrom(HypontechInverter.class));
		assertTrue(HybridInverterControl.class.isAssignableFrom(HypontechInverter.class));
		assertTrue(HybridInverterAlarms.class.isAssignableFrom(HypontechInverter.class));
		assertTrue(ManagedSymmetricPvInverter.class.isAssignableFrom(HypontechInverter.class));
		assertTrue(ManagedSymmetricPvInverter.class.isAssignableFrom(HypontechInverterImpl.class));
		assertTrue(ModbusComponent.class.isAssignableFrom(HypontechInverter.class));

		assertFalse(implementsManagedSymmetricEss(HypontechInverter.class));
		assertFalse(implementsManagedSymmetricEss(HypontechInverterImpl.class));

		var sut = new HypontechInverterImpl();
		assertNotNull(sut.channel(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY));
		assertSharedChannelsExist(sut, RtsPvInverter.ChannelId.values());
		assertSharedChannelsExist(sut, HybridInverter.ChannelId.values());
		assertSharedChannelsExist(sut, HybridInverterControl.ChannelId.values());
		assertSharedChannelsExist(sut, HybridInverterAlarms.ChannelId.values());
		assertSharedChannelsExist(sut, ManagedSymmetricPvInverter.ChannelId.values());
	}

	@Test
	public void testCanonicalActivePowerLimitQueuesExistingRegister40441Payload() throws Exception {
		// Given: writes are enabled and the existing register-40441 write path is present.
		var sut = new HypontechInverterImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
				.next(new TestCase("init write enabled"));
		var protocol = getHypontechProtocol(sut);

		// When: the canonical managed-PV channel receives a watt limit.
		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(12_000);

		// Then: the existing normalized request listener accepts and queues the same 40441
		// whole-kW payload; no extra write task is introduced.
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 12);
		assertWriteTasks(protocol);
	}

	@Test
	public void testZeroExportControllerDispatchesToRegister40441() throws Exception {
		var inverter = new HypontechInverterImpl();
		var inverterConfig = MyConfig.create() //
				.setId("inverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(1) //
				.setWriteEnabled(true) //
				.setMaxActivePower(15_000) //
				.build();
		new ComponentTest(inverter) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(inverterConfig) //
				.next(new TestCase("controller input") //
						.input(ElectricityMeter.ChannelId.ACTIVE_POWER, 12_000));

		var meter = new DummyElectricityMeter("meter0");
		meter._setActivePower(5_000);
		meter.getActivePowerChannel().nextProcessImage();
		var componentManager = new DummyComponentManager() //
				.addComponent(inverter) //
				.addComponent(meter);

		var controller = new ControllerPvInverterZeroExportImpl();
		setAttributeViaReflection(controller, "componentManager", componentManager);
		activateZeroExportController(controller, inverterConfig);

		// Grid import is 5 kW. At Kp=0.25 the controller adds 1.25 kW
		// to measured inverter power (12 kW), then HYPONTECH floors 13.25 kW
		// to the whole-kW unit of register 40441.
		controller.run();

		var protocol = getHypontechProtocol(inverter);
		assertTask(protocol.activePowerLimitRawElement(), FC16WriteRegistersTask.class, 40441);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 13);
	}

	@Test
	public void testCanonicalActivePowerLimitNullReleasesToVerifiedMaximum() throws Exception {
		// Given: writes are enabled and a verified maximum active-power value is already
		// available from managed metadata.
		var sut = new HypontechInverterImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
				.next(new TestCase("init write enabled"));
		var protocol = getHypontechProtocol(sut);
		ManagedSymmetricPvInverter managed = ManagedSymmetricPvInverter.class.cast(sut);

		// When: the controller resets the canonical limit with null.
		managed.setActivePowerLimit((Integer) null);

		// Then: null does not write an unsafe null/zero; it releases curtailment by
		// forwarding the verified maximum through the existing 40441 path.
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 15);
		assertWriteTasks(protocol);
	}

	@Test
	public void testConfiguredMaxActivePowerIsExposedForControllerDiscovery() throws Exception {
		// Given/When: the component is activated with explicit whole-kW maximum power.
		var sut = new HypontechInverterImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
				.next(new TestCase("configured maximum metadata") //
						.output(ManagedSymmetricPvInverter.ChannelId.MAX_ACTIVE_POWER, 15_000) //
						.output(ManagedSymmetricPvInverter.ChannelId.MAX_APPARENT_POWER, 15_000));

		// Then: reactive-power maximum remains unavailable because it is unsupported.
		assertEquals(null, sut.getMaxReactivePower().get());
	}

	@Test
	public void testCanonicalActivePowerLimitFloorsToExistingRegister40441WholeKwPayload() throws Exception {
		// Given: writes are enabled and register 40441 accepts whole-kW payloads.
		var sut = new HypontechInverterImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
				.next(new TestCase("init write enabled"));
		var protocol = getHypontechProtocol(sut);

		// When: the controller writes an arbitrary non-negative watt value.
		ManagedSymmetricPvInverter.class.cast(sut).setActivePowerLimit(12_999);

		// Then: the existing 40441 path safely floors to 12 whole kW instead of
		// rejecting a usable controller output.
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 12);
	}

	@Test
	public void testConfiguredMaxActivePowerMustBePositiveWholeKw() {
		// Given/When/Then: invalid capacity metadata is rejected during activation
		// instead of being silently rounded for controller discovery.
		assertInvalidMaxActivePowerRejected(0);
		assertInvalidMaxActivePowerRejected(-1_000);
		assertInvalidMaxActivePowerRejected(15_500);
	}

	@Test
	public void testReadTasksGroupContiguousRegistersAndPrioritizeStaticDataLow() {
		var protocol = getProtocol(new HypontechInverterImpl());

		assertReadTask(protocol, 30010, 24, Priority.LOW);
		assertReadTask(protocol, 30039, 2, Priority.LOW);
		assertReadTask(protocol, 30127, 7, Priority.HIGH);
		assertReadTask(protocol, 30161, 5, Priority.HIGH);
		assertReadTask(protocol, 30186, 6, Priority.HIGH);
		assertEquals(18L, protocol.getTaskManager().getTasks().stream()
				.filter(FC3ReadRegistersTask.class::isInstance).count());
	}

	@Test
	public void testDocumentCheckedTelemetry() throws Exception {
		// Given: document-checked HYPONTECH rows from the workbook, using FC03
		// Holding Register addresses exactly as listed.
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(30100, concat(s32(12_345), s32(0), s32(-4_321), 98, 0, s32(-1_500), 0, 0, 2)) //
				.withRegister(30115, 2) //
				.withRegisters(30127, s32(5_678), float32(12.5f)) //
				.withRegisters(30131, 2301, 111, 2312, 0, 2298) //
				.withRegister(30140, 5000) //
				.withRegistersFloat32(30156, 123.5f, 45.25f) //
				.withRegisters(30161, concat(2, s32(-2_750), 5101, 0xFFF6)) //
				.withRegistersFloat32(30174, 30.75f, 20.5f) //
				.withRegister(30182, 66) //
				.withRegisters(30186, s32(-600), s32(700), s32(-800)) //
				.withRegisters(30010, ascii("HYPONTECH")) //
				.withRegisters(30018, ascii("HYB-10K")) //
				.withRegisters(30026, ascii("SN1234567890")) //
				.withRegisters(30039, 0x0102, 0x0304);

		var sut = new HypontechInverterImpl();

		// When/Then: the Modbus cycle reads S16, S32 and Float32 fixtures and normalizes
		// to W, mV, mA and Wh.
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", bridge) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
					.next(new TestCase("document checked telemetry and first low-priority metadata block")) //
					.next(new TestCase("second low-priority metadata block")) //
					.deactivate();

		assertEquals("HYPONTECH", sut.channel(HypontechInverter.ChannelId.MANUFACTURER).value().get());
		assertEquals("HYB-10K", sut.channel(HypontechInverter.ChannelId.MODEL).value().get());
		assertEquals("SN1234567890", sut.channel(HypontechInverter.ChannelId.SERIAL_NUMBER).value().get());
		assertEquals("1.2.3.4", sut.channel(HypontechInverter.ChannelId.SOFTWARE_VERSION).value().get());
		assertEquals(Integer.valueOf(HypontechOperatingState.FAULT.getValue()), sut.getOperatingState().get());
		assertEquals(Integer.valueOf(230_100), sut.getVoltageL1().get());
		assertEquals(Integer.valueOf(11_100), sut.getCurrentL1().get());
		assertEquals(Integer.valueOf(231_200), sut.getVoltageL2().get());
		assertEquals(Integer.valueOf(229_800), sut.getVoltageL3().get());
		assertEquals(Integer.valueOf(12_345), sut.getActivePower().get());
		assertEquals(Integer.valueOf(-1_500), sut.getGridActivePower().get());
		assertEquals(Integer.valueOf(-600), sut.getGridActivePowerL1().get());
		assertEquals(Integer.valueOf(700), sut.getGridActivePowerL2().get());
		assertEquals(Integer.valueOf(-800), sut.getGridActivePowerL3().get());
		assertEquals(null, sut.getActivePowerL1().get());
		assertEquals(null, sut.getActivePowerL2().get());
		assertEquals(null, sut.getActivePowerL3().get());
		assertEquals(Integer.valueOf(-4_321), sut.getReactivePower().get());
		assertEquals(Integer.valueOf(5_678), sut.getDcPower().get());
		assertEquals(Integer.valueOf(980), sut.getPowerFactor().get());
		assertEquals(Long.valueOf(12_500), sut.getActiveProductionEnergy().get());
		assertEquals(null, sut.getActiveProductionEnergyToday().get());
		assertEquals(Long.valueOf(123_500), sut.getGridImportEnergy().get());
		assertEquals(Long.valueOf(45_250), sut.getGridExportEnergy().get());
		assertEquals(Integer.valueOf(HypontechBatteryState.DISCHARGING.getValue()), sut.getBatteryState().get());
		assertEquals(Integer.valueOf(-2_750), sut.getBatteryPower().get());
		assertEquals(Integer.valueOf(510_100), sut.getBatteryVoltage().get());
		assertEquals(Integer.valueOf(-1_000), sut.getBatteryCurrent().get());
		assertEquals(Long.valueOf(30_750), sut.getBatteryChargeEnergy().get());
		assertEquals(Long.valueOf(20_500), sut.getBatteryDischargeEnergy().get());
		assertEquals(Integer.valueOf(66), sut.getBatterySoc().get());
		assertEquals(Boolean.FALSE, sut.getWriteEnabled().get());
		assertEquals(Long.valueOf(0), sut.getSupportedAlarmMask().get());
		assertEquals(Boolean.FALSE, sut.getAlarmDataValid().get());
	}

	@Test
	public void testUnsupportedStateAndMissingRegisterStayUnavailable() throws Exception {
		// Given: invalid enum values and intentionally absent SOC register.
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegister(30115, 9) //
				.withRegister(30161, 99);

		// When/Then: unsupported enums and missing registers do not become zero.
		new ComponentTest(new HypontechInverterImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", bridge) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
				.next(new TestCase("unsupported states") //
						.output(HybridInverter.ChannelId.OPERATING_STATE, null) //
						.output(HybridInverter.ChannelId.BATTERY_STATE, null) //
						.output(HybridInverter.ChannelId.BATTERY_SOC, null)) //
				.deactivate();
	}

	@Test
	public void testDisabledWritesAreRejectedWithoutRawModbusPendingValue() throws Exception {
		// Given: writes are disabled by default.
		var sut = new HypontechInverterImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.build()) //
				.next(new TestCase("init write disabled") //
						.output(HybridInverterControl.ChannelId.WRITE_ENABLED, false));

		var protocol = getHypontechProtocol(sut);
		assertWriteTasks(protocol);

		// When/Then: each supported request is rejected before a raw Modbus value is
		// queued.
		sut.setEmsModeRequest(4);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.emsModeRawElement().getNextWriteValueAndReset());

		sut.setBatteryPowerReferenceRequest(-125);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.batteryPowerReferenceRawElement().getNextWriteValueAndReset());

		sut.setActivePowerLimitRequest(12_000);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());

		sut.setReserveSocRequest(44);
		assertRejected(componentTest, sut, CommandResult.REJECTED_DISABLED);
		assertNull(protocol.reserveSocRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testInvalidWriteValuesAreRejectedWithoutRawModbusPendingValue() throws Exception {
		// Given: writes are enabled but requests still have to pass enum/range checks.
		var sut = new HypontechInverterImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
				.next(new TestCase("init write enabled") //
						.output(HybridInverterControl.ChannelId.WRITE_ENABLED, true));

		var protocol = getHypontechProtocol(sut);

		// When/Then: invalid EMS modes, signed-word references, active-power limits and
		// reserve SOC values are rejected without reaching the raw elements.
		sut.setEmsModeRequest(5);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.emsModeRawElement().getNextWriteValueAndReset());

		sut.setBatteryPowerReferenceRequest(32_768);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.batteryPowerReferenceRawElement().getNextWriteValueAndReset());

		sut.setActivePowerLimitRequest(-1);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());

		sut.setReserveSocRequest(101);
		assertRejected(componentTest, sut, CommandResult.REJECTED_RANGE);
		assertNull(protocol.reserveSocRawElement().getNextWriteValueAndReset());
	}

	@Test
	public void testAcceptedWritesQueueExactRawPayloadOnceAndReadBack() throws Exception {
		// Given: the four writable HYPONTECH registers exist in the dummy process image.
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(40441, 0, 0) //
				.withRegister(40901, 0) //
				.withRegister(40903, 0) //
				.withRegister(40907, 0);
		var sut = new HypontechInverterImpl();
		var componentTest = new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", bridge) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
				.next(new TestCase("init write enabled"));

		var protocol = getHypontechProtocol(sut);

		// When/Then: EMS mode uses FC06 register 40907 and one raw word.
		sut.setEmsModeRequest(4);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.emsModeRawElement(), 4);
		sut.setEmsModeRequest(4);
		executeWriteTask(protocol.emsModeRawElement(), bridge, FC6WriteRegisterTask.class, 40907);

		// When/Then: battery reference uses FC06 register 40901 and signed 0.1 %Pn.
		sut.setBatteryPowerReferenceRequest(-125);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.batteryPowerReferenceRawElement(), 0xFF83);
		sut.setBatteryPowerReferenceRequest(-125);
		executeWriteTask(protocol.batteryPowerReferenceRawElement(), bridge, FC6WriteRegisterTask.class, 40901);

		// When/Then: active-power limit uses FC16 starting 40441 and S32 kW payload.
		sut.setActivePowerLimitRequest(12_000);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 12);
		sut.setActivePowerLimitRequest(12_000);
		executeWriteTask(protocol.activePowerLimitRawElement(), bridge, FC16WriteRegistersTask.class, 40441);

		// When/Then: reserve SOC uses FC06 register 40903 and a percent word.
		sut.setReserveSocRequest(44);
		assertAccepted(componentTest, sut);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
		sut.setReserveSocRequest(44);
		executeWriteTask(protocol.reserveSocRawElement(), bridge, FC6WriteRegisterTask.class, 40903);

		// Then: a read cycle exposes the matching readbacks where the device has RW
		// registers.
		componentTest.next(new TestCase("read accepted command readbacks") //
				.output(HybridInverterControl.ChannelId.EMS_MODE_READBACK, 4) //
				.output(HybridInverterControl.ChannelId.BATTERY_POWER_REFERENCE_READBACK, -125) //
				.output(HybridInverterControl.ChannelId.ACTIVE_POWER_LIMIT_READBACK, 12_000) //
				.output(HybridInverterControl.ChannelId.RESERVE_SOC_READBACK, 44));
	}

	@Test
	public void testHandleEventDoesNotExecuteWriteCycles() throws Exception {
		// Given: a queued command on the raw element.
		var sut = new HypontechInverterImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
				.next(new TestCase("init"));
		var protocol = getHypontechProtocol(sut);
		sut.setReserveSocRequest(50);

		// When: the component event handler is called directly.
		sut.handleEvent(new org.osgi.service.event.Event(EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, java.util.Map.of()));

		// Then: the raw value is still pending because DummyModbusBridge does not run the
		// write cycle through handleEvent().
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 50);
	}

	@Test
	public void testRejectedEmsModeClearsOnlyQueuedEmsModeRawValue() throws Exception {
		// Given: every command has an accepted pending raw value.
		var sut = activateWriteEnabledSut();
		var protocol = getHypontechProtocol(sut);
		queueAllCommands(sut);

		// When: EMS mode receives a later invalid request before the write task consumes
		// the accepted value.
		sut.setEmsModeRequest(5);

		// Then: only EMS mode is cleared; the other command queues are preserved.
		assertNull(protocol.emsModeRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.batteryPowerReferenceRawElement(), 0xFF83);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 12);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
	}

	@Test
	public void testRejectedBatteryReferenceClearsOnlyQueuedBatteryReferenceRawValue() throws Exception {
		// Given: every command has an accepted pending raw value.
		var sut = activateWriteEnabledSut();
		var protocol = getHypontechProtocol(sut);
		queueAllCommands(sut);

		// When: battery reference receives a later invalid request before the write task
		// consumes the accepted value.
		sut.setBatteryPowerReferenceRequest(32_768);

		// Then: only battery reference is cleared; the other command queues are
		// preserved.
		assertRawPayloadOnce(protocol.emsModeRawElement(), 4);
		assertNull(protocol.batteryPowerReferenceRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 12);
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
	}

	@Test
	public void testRejectedActivePowerLimitClearsOnlyQueuedActivePowerLimitRawValue() throws Exception {
		// Given: every command has an accepted pending raw value.
		var sut = activateWriteEnabledSut();
		var protocol = getHypontechProtocol(sut);
		queueAllCommands(sut);

		// When: active-power limit receives a later invalid request before the write task
		// consumes the accepted value.
		sut.setActivePowerLimitRequest(-1);

		// Then: only active-power limit is cleared; the other command queues are
		// preserved.
		assertRawPayloadOnce(protocol.emsModeRawElement(), 4);
		assertRawPayloadOnce(protocol.batteryPowerReferenceRawElement(), 0xFF83);
		assertNull(protocol.activePowerLimitRawElement().getNextWriteValueAndReset());
		assertRawPayloadOnce(protocol.reserveSocRawElement(), 44);
	}

	@Test
	public void testRejectedReserveSocClearsOnlyQueuedReserveSocRawValue() throws Exception {
		// Given: every command has an accepted pending raw value.
		var sut = activateWriteEnabledSut();
		var protocol = getHypontechProtocol(sut);
		queueAllCommands(sut);

		// When: reserve SOC receives a later invalid request before the write task
		// consumes the accepted value.
		sut.setReserveSocRequest(101);

		// Then: only reserve SOC is cleared; the other command queues are preserved.
		assertRawPayloadOnce(protocol.emsModeRawElement(), 4);
		assertRawPayloadOnce(protocol.batteryPowerReferenceRawElement(), 0xFF83);
		assertRawPayloadOnce(protocol.activePowerLimitRawElement(), 0, 12);
		assertNull(protocol.reserveSocRawElement().getNextWriteValueAndReset());
	}

	private static HypontechInverterImpl activateWriteEnabledSut() throws Exception {
		var sut = new HypontechInverterImpl();
		new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(15_000) //
						.setWriteEnabled(true) //
						.build()) //
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

	private static void assertInvalidMaxActivePowerRejected(int maxActivePower) {
		var sut = new HypontechInverterImpl();
		var exception = assertThrows(Exception.class, () -> new ComponentTest(sut) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("inverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(1) //
						.setMaxActivePower(maxActivePower) //
						.build()));
		assertTrue(hasCause(exception, OpenemsException.class));
	}

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
		var current = throwable;
		while (current != null) {
			if (expectedType.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static void queueAllCommands(HypontechInverterImpl sut) throws Exception {
		sut.setEmsModeRequest(4);
		sut.setBatteryPowerReferenceRequest(-125);
		sut.setActivePowerLimitRequest(12_000);
		sut.setReserveSocRequest(44);
	}

	private static ModbusProtocol getProtocol(HypontechInverterImpl sut) {
		return invokeMethodWithoutArgumentsViaReflection(AbstractOpenemsModbusComponent.class, sut, "getModbusProtocol");
	}

	private static HypontechInverterProtocol getHypontechProtocol(HypontechInverterImpl sut) {
		return (HypontechInverterProtocol) getProtocol(sut);
	}

	private static void assertWriteTasks(HypontechInverterProtocol protocol) {
		assertTask(protocol.emsModeRawElement(), FC6WriteRegisterTask.class, 40907);
		assertTask(protocol.batteryPowerReferenceRawElement(), FC6WriteRegisterTask.class, 40901);
		assertTask(protocol.activePowerLimitRawElement(), FC16WriteRegistersTask.class, 40441);
		assertTask(protocol.reserveSocRawElement(), FC6WriteRegisterTask.class, 40903);
		var writeTaskCount = protocol.getTaskManager().getTasks().stream().filter(AbstractWriteTask.class::isInstance).count();
		assertEquals(4L, writeTaskCount);
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

	private static void assertTask(ModbusRegisterElement<?, ?> element, Class<?> taskType, int startAddress) {
		var task = element.getModbusTask();
		assertTrue(taskType.isInstance(task));
		assertEquals(startAddress, task.getStartAddress());
	}

	private static void executeWriteTask(ModbusRegisterElement<?, ?> element, DummyModbusBridge bridge, Class<?> taskType,
			int startAddress) {
		assertTask(element, taskType, startAddress);
		assertEquals(io.openems.edge.bridge.modbus.api.task.Task.ExecuteState.OK, element.getModbusTask().execute(bridge));
		assertNull(element.getNextWriteValueAndReset());
	}

	private static void assertAccepted(ComponentTest componentTest, HypontechInverterImpl sut) throws Exception {
		componentTest.next(new TestCase("accepted command result"));
		assertEquals(Integer.valueOf(CommandResult.ACCEPTED.getValue()), sut.getLastCommandResult().get());
		assertEquals("", sut.getLastCommandRejectionReason().get());
	}

	private static void assertRejected(ComponentTest componentTest, HypontechInverterImpl sut, CommandResult result)
			throws Exception {
		componentTest.next(new TestCase("rejected command result"));
		assertEquals(Integer.valueOf(result.getValue()), sut.getLastCommandResult().get());
		assertTrue(sut.getLastCommandRejectionReason().get() instanceof String reason && !reason.isBlank());
	}

	private static void assertRawPayloadOnce(ModbusRegisterElement<?, ?> element, int... expected) {
		var registers = element.getNextWriteValueAndReset();
		assertEquals(expected.length, registers.length);
		for (var i = 0; i < expected.length; i++) {
			assertEquals(expected[i], unsigned(registers[i]));
		}
		assertNull(element.getNextWriteValueAndReset());
	}

	private static int unsigned(Register register) {
		return register.getValue() & 0xFFFF;
	}

	private static int[] s32(int value) {
		return new int[] { value >> 16, value & 0xFFFF };
	}

	private static int[] float32(float value) {
		var bits = Float.floatToIntBits(value);
		return s32(bits);
	}

	private static int[] concat(Object... values) {
		var size = 0;
		for (var value : values) {
			size += value instanceof int[] a ? a.length : 1;
		}
		var result = new int[size];
		var index = 0;
		for (var value : values) {
			if (value instanceof int[] a) {
				System.arraycopy(a, 0, result, index, a.length);
				index += a.length;
			} else {
				result[index++] = ((Integer) value).intValue();
			}
		}
		return result;
	}

	private static int[] ascii(String value) {
		var padded = String.format("%-16s", value);
		var registers = new int[8];
		for (var i = 0; i < registers.length; i++) {
			registers[i] = padded.charAt(i * 2) << 8 | padded.charAt(i * 2 + 1);
		}
		return registers;
	}

	private static void assertSharedChannelsExist(HypontechInverterImpl sut, ChannelId[] channelIds) {
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
