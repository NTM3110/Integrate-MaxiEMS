package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;

import org.junit.Test;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;

public class RtsPvInverterAlarmsTest {

	private static final class DummyAlarms extends AbstractDummyOpenemsComponent<DummyAlarms>
			implements RtsPvInverterAlarms {

		private DummyAlarms() {
			super("pvInverter0", OpenemsComponent.ChannelId.values(), RtsPvInverterAlarms.ChannelId.values());
		}

		@Override
		protected DummyAlarms self() {
			return this;
		}
	}

	@Test
	public void testStableAlarmBits() {
		assertEquals(30, RtsPvInverterAlarmId.values().length);
		long combinedMask = 0L;
		for (var alarmId : RtsPvInverterAlarmId.values()) {
			assertEquals(alarmId.ordinal(), alarmId.bit());
			assertEquals(0L, combinedMask & alarmId.mask());
			combinedMask |= alarmId.mask();
		}
		assertEquals(RtsPvInverterAlarmId.ALL_MASK, combinedMask);
		assertFalse(RtsPvInverterAlarmId.COMMUNICATION_LOST.channelId().isPresent());
	}

	@Test
	public void testInitializeApplyAndInvalidateSnapshot() {
		var sut = new DummyAlarms();
		var mask = RtsPvInverterAlarmId.maskOf(RtsPvInverterAlarmId.COMMUNICATION_LOST,
				RtsPvInverterAlarmId.DEVICE_OFFLINE, RtsPvInverterAlarmId.INVALID_DATA,
				RtsPvInverterAlarmId.GRID_OVER_VOLTAGE);

		sut.initializeRtsPvInverterAlarms(mask);
		assertEquals(Long.valueOf(mask), sut.getSupportedAlarmMaskChannel().getNextValue().get());
		assertFalse(sut.getAlarmDataValidChannel().getNextValue().get());
		assertNull(sut.getAlarmChannel(RtsPvInverterAlarmId.DEVICE_OFFLINE).getNextValue().get());

		var snapshot = new EnumMap<RtsPvInverterAlarmId, Boolean>(RtsPvInverterAlarmId.class);
		snapshot.put(RtsPvInverterAlarmId.DEVICE_OFFLINE, false);
		snapshot.put(RtsPvInverterAlarmId.INVALID_DATA, false);
		snapshot.put(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE, true);
		sut.applyRtsPvInverterAlarmSnapshot(mask, snapshot);
		assertTrue(sut.getAlarmDataValidChannel().getNextValue().get());
		assertFalse(sut.getAlarmChannel(RtsPvInverterAlarmId.DEVICE_OFFLINE).getNextValue().get());
		assertTrue(sut.getAlarmChannel(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE).getNextValue().get());
		assertNull(sut.getAlarmChannel(RtsPvInverterAlarmId.GRID_UNDER_VOLTAGE).getNextValue().get());

		sut.invalidateRtsPvInverterAlarmData();
		assertFalse(sut.getAlarmDataValidChannel().getNextValue().get());
		assertNull(sut.getAlarmChannel(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE).getNextValue().get());
		assertFalse(sut.getAlarmChannel(RtsPvInverterAlarmId.INVALID_DATA).getNextValue().get());
	}

	@Test
	public void testSnapshotMustContainEverySupportedDeviceAlarm() {
		var sut = new DummyAlarms();
		var mask = RtsPvInverterAlarmId.GRID_FAULT.mask();
		assertThrows(IllegalArgumentException.class,
				() -> sut.applyRtsPvInverterAlarmSnapshot(mask, new EnumMap<>(RtsPvInverterAlarmId.class)));
		assertThrows(IllegalArgumentException.class,
				() -> sut.initializeRtsPvInverterAlarms(1L << 31));
		assertThrows(IllegalArgumentException.class,
				() -> sut.getAlarmChannel(RtsPvInverterAlarmId.COMMUNICATION_LOST));
	}
}
