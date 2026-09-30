package io.openems.edge.alarm.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

import io.openems.common.channel.Level;

public class AlarmIdTest {

	@Test
	public void testStableBitsAndProfiles() {
		assertEquals(0, AlarmId.COMMUNICATION_LOST.bit());
		assertEquals(4, AlarmId.UNSUPPORTED_DEVICE.bit());
		assertEquals(5, AlarmId.GRID_OVER_VOLTAGE.bit());
		assertEquals(29, AlarmId.DC_FAULT.bit());
		assertEquals(32, AlarmId.LOGGER_INTERNAL_FAULT.bit());
		assertEquals(37, AlarmId.DOWNSTREAM_COMMUNICATION_FAULT.bit());

		assertEquals(AlarmProfile.DEVICE, AlarmId.INVALID_DATA.profile());
		assertEquals(AlarmProfile.INVERTER, AlarmId.ARC_FAULT.profile());
		assertEquals(AlarmProfile.LOGGER, AlarmId.LOGGER_STORAGE_FAULT.profile());

		var bits = new HashSet<Integer>();
		for (var alarmId : AlarmId.values()) {
			assertTrue(bits.add(alarmId.bit()));
		}
		assertFalse(bits.contains(30));
		assertFalse(bits.contains(31));
	}

	@Test
	public void testMasksContainOnlyTheirProfiles() {
		for (var alarmId : AlarmId.values()) {
			var profileMask = switch (alarmId.profile()) {
			case DEVICE -> AlarmId.DEVICE_MASK;
			case INVERTER -> AlarmId.INVERTER_MASK;
			case LOGGER -> AlarmId.LOGGER_MASK;
			};
			assertTrue(AlarmId.isSupported(profileMask, alarmId));
		}

		assertEquals(AlarmId.ALL_MASK, AlarmId.DEVICE_MASK | AlarmId.INVERTER_MASK | AlarmId.LOGGER_MASK);
		assertEquals(0, AlarmId.ALL_MASK & ((1L << 30) | (1L << 31)));
		assertEquals(AlarmId.GRID_FAULT.mask() | AlarmId.DC_FAULT.mask(),
				AlarmId.maskOf(AlarmId.GRID_FAULT, AlarmId.DC_FAULT));
		assertFalse(AlarmId.isSupported(AlarmId.DEVICE_MASK, AlarmId.GRID_FAULT));
	}

	@Test
	public void testSeverityAndTextContract() {
		assertEquals(Level.WARNING, AlarmId.COMMUNICATION_LOST.level());
		assertEquals("Communication Lost", AlarmId.COMMUNICATION_LOST.text());
		assertEquals(Level.FAULT, AlarmId.GRID_OVER_VOLTAGE.level());
		assertEquals(Level.FAULT, AlarmId.LOGGER_STORAGE_FAULT.level());
		assertTrue(Arrays.stream(AlarmId.values()).allMatch(alarmId -> !alarmId.text().isBlank()));
	}
}
