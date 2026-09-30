package io.openems.edge.alarm.api;

import static io.openems.edge.common.test.TestUtils.activateNextProcessImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;

public class AlarmNatureTest {

	@Test
	public void testChannelContract() {
		assertEquals(OpenemsType.LONG, DeviceAlarms.ChannelId.SUPPORTED_ALARM_MASK.doc().getType());
		assertEquals(PersistencePriority.MEDIUM,
				DeviceAlarms.ChannelId.SUPPORTED_ALARM_MASK.doc().getPersistencePriority());
		assertEquals(OpenemsType.BOOLEAN, DeviceAlarms.ChannelId.ALARM_DATA_VALID.doc().getType());
		assertEquals(PersistencePriority.HIGH,
				DeviceAlarms.ChannelId.ALARM_DATA_VALID.doc().getPersistencePriority());
		assertEquals(PersistencePriority.HIGH,
				InverterAlarms.ChannelId.GRID_OVER_VOLTAGE.doc().getPersistencePriority());
		assertEquals(PersistencePriority.HIGH,
				LoggerAlarms.ChannelId.LOGGER_STORAGE_FAULT.doc().getPersistencePriority());
	}

	@Test
	public void testProfilesAcceptOnlyTheirAlarmMasks() {
		var device = new DummyDeviceAlarms("device0");
		var inverter = new DummyInverterAlarms("inverter0");
		var logger = new DummyLoggerAlarms("logger0");

		device.initializeAlarms(AlarmId.DEVICE_OFFLINE.mask());
		inverter.initializeAlarms(AlarmId.DEVICE_OFFLINE.mask() | AlarmId.GRID_FAULT.mask());
		logger.initializeAlarms(AlarmId.DEVICE_OFFLINE.mask() | AlarmId.LOGGER_INTERNAL_FAULT.mask());

		assertThrows(IllegalArgumentException.class,
				() -> device.initializeAlarms(AlarmId.GRID_FAULT.mask()));
		assertThrows(IllegalArgumentException.class,
				() -> inverter.initializeAlarms(AlarmId.LOGGER_INTERNAL_FAULT.mask()));
		assertThrows(IllegalArgumentException.class,
				() -> logger.initializeAlarms(AlarmId.GRID_FAULT.mask()));
	}

	@Test
	public void testInitializeAndApplyCompleteSnapshot() {
		var sut = new DummyInverterAlarms("inverter0");
		var mask = AlarmId.maskOf(AlarmId.DEVICE_OFFLINE, AlarmId.GRID_OVER_VOLTAGE);

		sut.initializeAlarms(mask);
		activateNextProcessImage(sut);

		assertEquals(Long.valueOf(mask), sut.getSupportedAlarmMask().get());
		assertEquals(Boolean.FALSE, sut.getAlarmDataValid().get());
		assertNull(sut.getAlarm(AlarmId.DEVICE_OFFLINE).get());
		assertNull(sut.getAlarm(AlarmId.GRID_OVER_VOLTAGE).get());

		sut.applyAlarmSnapshot(mask, Map.of( //
				AlarmId.DEVICE_OFFLINE, false, //
				AlarmId.GRID_OVER_VOLTAGE, true));
		activateNextProcessImage(sut);

		assertEquals(Boolean.TRUE, sut.getAlarmDataValid().get());
		assertEquals(Boolean.FALSE, sut.getAlarm(AlarmId.DEVICE_OFFLINE).get());
		assertEquals(Boolean.TRUE, sut.getAlarm(AlarmId.GRID_OVER_VOLTAGE).get());
	}

	@Test
	public void testSnapshotRejectsMissingAndUndefinedAlarms() {
		var sut = new DummyInverterAlarms("inverter0");
		var mask = AlarmId.maskOf(AlarmId.DEVICE_OFFLINE, AlarmId.GRID_FAULT);

		assertThrows(IllegalArgumentException.class,
				() -> sut.applyAlarmSnapshot(mask, Map.of(AlarmId.DEVICE_OFFLINE, false)));
		assertThrows(IllegalArgumentException.class, () -> sut.initializeAlarms(1L << 63));
		assertThrows(IllegalArgumentException.class,
				() -> sut.getAlarmChannel(AlarmId.LOGGER_INTERNAL_FAULT));
	}

	@Test
	public void testInvalidationKeepsLocalDiagnostics() {
		var sut = new DummyInverterAlarms("inverter0");
		var mask = AlarmId.maskOf(AlarmId.INVALID_DATA, AlarmId.DEVICE_OFFLINE, AlarmId.GRID_FAULT);
		sut.applyAlarmSnapshot(mask, Map.of( //
				AlarmId.INVALID_DATA, true, //
				AlarmId.DEVICE_OFFLINE, false, //
				AlarmId.GRID_FAULT, true));
		activateNextProcessImage(sut);

		sut.invalidateAlarmData();
		activateNextProcessImage(sut);

		assertEquals(Boolean.FALSE, sut.getAlarmDataValid().get());
		assertEquals(Boolean.TRUE, sut.getAlarm(AlarmId.INVALID_DATA).get());
		assertNull(sut.getAlarm(AlarmId.DEVICE_OFFLINE).get());
		assertNull(sut.getAlarm(AlarmId.GRID_FAULT).get());
	}

	@Test
	public void testDeviceSnapshotDoesNotOverwriteTransportAlarm() {
		var sut = new DummyDeviceAlarms("device0");
		sut._setAlarm(AlarmId.COMMUNICATION_LOST, true);
		sut.applyAlarmSnapshot(AlarmId.DEVICE_OFFLINE.mask(), Map.of(AlarmId.DEVICE_OFFLINE, false));
		activateNextProcessImage(sut);

		assertEquals(Boolean.TRUE, sut.getAlarm(AlarmId.COMMUNICATION_LOST).get());
		assertEquals(Boolean.FALSE, sut.getAlarm(AlarmId.DEVICE_OFFLINE).get());
	}

	@Test
	public void testAlarmIdentityClassification() {
		assertFalse(AlarmId.INVALID_DATA.isDeviceDerived());
		assertTrue(AlarmId.DEVICE_OFFLINE.isDeviceDerived());
		assertTrue(AlarmId.GRID_FAULT.isDeviceDerived());
		assertTrue(AlarmId.LOGGER_STORAGE_FAULT.isDeviceDerived());
	}

	private static final class DummyDeviceAlarms extends AbstractDummyOpenemsComponent<DummyDeviceAlarms>
			implements DeviceAlarms {

		private DummyDeviceAlarms(String id) {
			super(id, OpenemsComponent.ChannelId.values(), DeviceAlarms.ChannelId.values());
		}

		@Override
		protected DummyDeviceAlarms self() {
			return this;
		}
	}

	private static final class DummyInverterAlarms extends AbstractDummyOpenemsComponent<DummyInverterAlarms>
			implements InverterAlarms {

		private DummyInverterAlarms(String id) {
			super(id, OpenemsComponent.ChannelId.values(), DeviceAlarms.ChannelId.values(),
					InverterAlarms.ChannelId.values());
		}

		@Override
		protected DummyInverterAlarms self() {
			return this;
		}
	}

	private static final class DummyLoggerAlarms extends AbstractDummyOpenemsComponent<DummyLoggerAlarms>
			implements LoggerAlarms {

		private DummyLoggerAlarms(String id) {
			super(id, OpenemsComponent.ChannelId.values(), DeviceAlarms.ChannelId.values(),
					LoggerAlarms.ChannelId.values());
		}

		@Override
		protected DummyLoggerAlarms self() {
			return this;
		}
	}
}
