package io.openems.edge.pvinverter.solis.ongrid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.pvinverter.api.RtsPvInverterAlarmId;

public class SolisOnGridFaultMapperTest {

	@Test
	public void mapsDedicatedFaultBits() {
		var snapshot = SolisOnGridFaultMapper.map(//
				bits(0, 1, 2, 3, 5, 6, 7), // Grid faults
				bits(0, 3, 5, 8), // DC faults
				bits(3, 4), // Isolation and leakage
				bits(1), // Arc fault
				false, false, false);

		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_UNDER_VOLTAGE));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_OVER_FREQUENCY));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_UNDER_FREQUENCY));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_CONNECTION_FAILURE));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_VOLTAGE_IMBALANCE));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_FREQUENCY_ABNORMAL));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.DC_OVER_VOLTAGE));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.DC_UNDER_VOLTAGE));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.DC_OVER_CURRENT));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.DC_REVERSE_POLARITY));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.PV_ISOLATION_FAULT));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.LEAKAGE_CURRENT_HIGH));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.ARC_FAULT));
	}

	@Test
	public void aggregatesVendorSpecificFaultBits() {
		var snapshot = SolisOnGridFaultMapper.map(bits(4), bits(1), 0, 0, true, true, true);

		assertTrue(snapshot.get(RtsPvInverterAlarmId.GRID_FAULT));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.DC_FAULT));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.INVALID_DATA));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.CONFIGURATION_ERROR));
		assertTrue(snapshot.get(RtsPvInverterAlarmId.UNSUPPORTED_DEVICE));
	}

	@Test
	public void clearsCompleteSupportedSnapshot() {
		var snapshot = SolisOnGridFaultMapper.map(0, 0, 0, 0, false, false, false);

		assertEquals(Long.bitCount(SolisOnGridFaultMapper.SUPPORTED_ALARM_MASK) - 1, snapshot.size());
		assertFalse(snapshot.containsKey(RtsPvInverterAlarmId.COMMUNICATION_LOST));
		assertTrue(snapshot.values().stream().noneMatch(Boolean::booleanValue));
	}

	@Test
	public void recognizesOnlyVerifiedDeviceTypes() {
		assertTrue(PvInverterSolisOnGridImpl.isSupportedDeviceType(0x1010));
		assertTrue(PvInverterSolisOnGridImpl.isSupportedDeviceType(0x1020));
		assertFalse(PvInverterSolisOnGridImpl.isSupportedDeviceType(0));
		assertFalse(PvInverterSolisOnGridImpl.isSupportedDeviceType(0x1030));
	}

	private static int bits(int... bits) {
		var result = 0;
		for (var bit : bits) {
			result |= 1 << bit;
		}
		return result;
	}
}
