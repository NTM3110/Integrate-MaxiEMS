package io.openems.edge.pvinverter.solis.ongrid;

import java.util.EnumMap;

import io.openems.edge.pvinverter.api.RtsPvInverterAlarmId;

/** Maps Solis fault words to the common RTS alarm model. */
public final class SolisOnGridFaultMapper {

	public static final long SUPPORTED_ALARM_MASK = RtsPvInverterAlarmId.maskOf(//
			RtsPvInverterAlarmId.COMMUNICATION_LOST, //
			RtsPvInverterAlarmId.INVALID_DATA, //
			RtsPvInverterAlarmId.CONFIGURATION_ERROR, //
			RtsPvInverterAlarmId.UNSUPPORTED_DEVICE, //
			RtsPvInverterAlarmId.GRID_OVER_VOLTAGE, //
			RtsPvInverterAlarmId.GRID_UNDER_VOLTAGE, //
			RtsPvInverterAlarmId.GRID_OVER_FREQUENCY, //
			RtsPvInverterAlarmId.GRID_UNDER_FREQUENCY, //
			RtsPvInverterAlarmId.GRID_VOLTAGE_IMBALANCE, //
			RtsPvInverterAlarmId.GRID_FREQUENCY_ABNORMAL, //
			RtsPvInverterAlarmId.GRID_CONNECTION_FAILURE, //
			RtsPvInverterAlarmId.GRID_FAULT, //
			RtsPvInverterAlarmId.DC_OVER_VOLTAGE, //
			RtsPvInverterAlarmId.DC_UNDER_VOLTAGE, //
			RtsPvInverterAlarmId.DC_OVER_CURRENT, //
			RtsPvInverterAlarmId.DC_REVERSE_POLARITY, //
			RtsPvInverterAlarmId.PV_ISOLATION_FAULT, //
			RtsPvInverterAlarmId.ARC_FAULT, //
			RtsPvInverterAlarmId.LEAKAGE_CURRENT_HIGH, //
			RtsPvInverterAlarmId.DC_FAULT);

	private static final int GRID_FAULT_01_MASK = bits(4, 8, 9, 10);
	private static final int GRID_FAULT_03_MASK = bits(0, 7);
	private static final int GRID_FAULT_04_MASK = bits(6, 7);
	private static final int DC_FAULT_02_MASK = bits(1, 2, 4, 7, 10);
	private static final int DC_FAULT_03_MASK = bits(8);
	private static final int DC_FAULT_04_MASK = bits(8);

	private SolisOnGridFaultMapper() {
	}

	public static EnumMap<RtsPvInverterAlarmId, Boolean> map(int fault01, int fault02, int fault03, int fault04,
			boolean invalidData, boolean configurationError, boolean unsupportedDevice) {
		var result = new EnumMap<RtsPvInverterAlarmId, Boolean>(RtsPvInverterAlarmId.class);
		for (var alarmId : RtsPvInverterAlarmId.values()) {
			if (alarmId != RtsPvInverterAlarmId.COMMUNICATION_LOST
					&& RtsPvInverterAlarmId.isSupported(SUPPORTED_ALARM_MASK, alarmId)) {
				result.put(alarmId, false);
			}
		}

		result.put(RtsPvInverterAlarmId.INVALID_DATA, invalidData);
		result.put(RtsPvInverterAlarmId.CONFIGURATION_ERROR, configurationError);
		result.put(RtsPvInverterAlarmId.UNSUPPORTED_DEVICE, unsupportedDevice);

		result.put(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE, hasBit(fault01, 0));
		result.put(RtsPvInverterAlarmId.GRID_UNDER_VOLTAGE, hasBit(fault01, 1));
		result.put(RtsPvInverterAlarmId.GRID_OVER_FREQUENCY, hasBit(fault01, 2));
		result.put(RtsPvInverterAlarmId.GRID_UNDER_FREQUENCY, hasBit(fault01, 3));
		result.put(RtsPvInverterAlarmId.GRID_CONNECTION_FAILURE, hasBit(fault01, 5));
		result.put(RtsPvInverterAlarmId.GRID_VOLTAGE_IMBALANCE, hasBit(fault01, 6));
		result.put(RtsPvInverterAlarmId.GRID_FREQUENCY_ABNORMAL, hasBit(fault01, 7));
		result.put(RtsPvInverterAlarmId.GRID_FAULT, (fault01 & GRID_FAULT_01_MASK) != 0
				|| (fault03 & GRID_FAULT_03_MASK) != 0 || (fault04 & GRID_FAULT_04_MASK) != 0);

		result.put(RtsPvInverterAlarmId.DC_OVER_VOLTAGE, hasBit(fault02, 0));
		result.put(RtsPvInverterAlarmId.DC_UNDER_VOLTAGE, hasBit(fault02, 3));
		result.put(RtsPvInverterAlarmId.DC_OVER_CURRENT, hasBit(fault02, 5) || hasBit(fault02, 6));
		result.put(RtsPvInverterAlarmId.DC_REVERSE_POLARITY, hasBit(fault02, 8));
		result.put(RtsPvInverterAlarmId.PV_ISOLATION_FAULT, hasBit(fault03, 3));
		result.put(RtsPvInverterAlarmId.LEAKAGE_CURRENT_HIGH, hasBit(fault03, 4));
		result.put(RtsPvInverterAlarmId.ARC_FAULT, hasBit(fault04, 1));
		result.put(RtsPvInverterAlarmId.DC_FAULT, (fault02 & DC_FAULT_02_MASK) != 0
				|| (fault03 & DC_FAULT_03_MASK) != 0 || (fault04 & DC_FAULT_04_MASK) != 0);
		return result;
	}

	private static boolean hasBit(int value, int bit) {
		return (value & 1 << bit) != 0;
	}

	private static int bits(int... bits) {
		var result = 0;
		for (var bit : bits) {
			result |= 1 << bit;
		}
		return result;
	}
}
