package io.openems.edge.pvinverter.api;

import java.util.Optional;

import io.openems.common.channel.Level;

/** Stable alarm identifiers and capability-mask bits for RTS PV inverters. */
public enum RtsPvInverterAlarmId {
	COMMUNICATION_LOST(0, Level.WARNING, "Communication Lost"),
	DEVICE_OFFLINE(1, Level.WARNING, "Device Offline"),
	INVALID_DATA(2, Level.WARNING, "Invalid Data"),
	CONFIGURATION_ERROR(3, Level.FAULT, "Configuration Error"),
	UNSUPPORTED_DEVICE(4, Level.FAULT, "Unsupported Device"),
	GRID_OVER_VOLTAGE(5, Level.FAULT, "Grid Over Voltage"),
	GRID_UNDER_VOLTAGE(6, Level.FAULT, "Grid Under Voltage"),
	GRID_OVER_FREQUENCY(7, Level.FAULT, "Grid Over Frequency"),
	GRID_UNDER_FREQUENCY(8, Level.FAULT, "Grid Under Frequency"),
	GRID_VOLTAGE_IMBALANCE(9, Level.WARNING, "Grid Voltage Imbalance"),
	GRID_FREQUENCY_ABNORMAL(10, Level.FAULT, "Grid Frequency Abnormal"),
	GRID_PHASE_LOSS(11, Level.FAULT, "Grid Phase Loss"),
	GRID_PHASE_SEQUENCE_ERROR(12, Level.FAULT, "Grid Phase Sequence Error"),
	GRID_CONNECTION_FAILURE(13, Level.FAULT, "Grid Connection Failure"),
	GRID_RECONNECT_FAILURE(14, Level.FAULT, "Grid Reconnect Failure"),
	REVERSE_POWER_DETECTED(15, Level.WARNING, "Reverse Power Detected"),
	WEAK_GRID_DETECTED(16, Level.WARNING, "Weak Grid Detected"),
	GRID_FAULT(17, Level.FAULT, "Grid Fault"),
	DC_OVER_VOLTAGE(18, Level.FAULT, "DC Over Voltage"),
	DC_UNDER_VOLTAGE(19, Level.WARNING, "DC Under Voltage"),
	DC_OVER_CURRENT(20, Level.FAULT, "DC Over Current"),
	DC_REVERSE_POLARITY(21, Level.FAULT, "DC Reverse Polarity"),
	PV_ISOLATION_FAULT(22, Level.FAULT, "PV Isolation Fault"),
	GROUND_FAULT(23, Level.FAULT, "Ground Fault"),
	ARC_FAULT(24, Level.FAULT, "Arc Fault"),
	LEAKAGE_CURRENT_HIGH(25, Level.FAULT, "Leakage Current High"),
	INSULATION_RESISTANCE_LOW(26, Level.FAULT, "Insulation Resistance Low"),
	PV_STRING_FAULT(27, Level.WARNING, "PV String Fault"),
	PV_INPUT_FAILURE(28, Level.WARNING, "PV Input Failure"),
	DC_FAULT(29, Level.FAULT, "DC Fault");

	public static final long ALL_MASK = (1L << 30) - 1;

	private final int bit;
	private final Level level;
	private final String text;

	private RtsPvInverterAlarmId(int bit, Level level, String text) {
		this.bit = bit;
		this.level = level;
		this.text = text;
	}

	public int bit() {
		return this.bit;
	}

	public long mask() {
		return 1L << this.bit;
	}

	public Level level() {
		return this.level;
	}

	public String text() {
		return this.text;
	}

	/**
	 * Returns true for alarms whose current value comes from device status/fault
	 * data. Local integration diagnostics remain valid independently.
	 *
	 * @return true for device-derived alarms
	 */
	public boolean isDeviceDerived() {
		return this == DEVICE_OFFLINE || this.bit >= GRID_OVER_VOLTAGE.bit;
	}

	/**
	 * Gets the common state channel. Communication Lost deliberately reuses the
	 * communication channel of the transport bundle and has no duplicate here.
	 *
	 * @return state Channel-ID, or empty for Communication Lost
	 */
	public Optional<RtsPvInverterAlarms.ChannelId> channelId() {
		if (this == COMMUNICATION_LOST) {
			return Optional.empty();
		}
		return Optional.of(RtsPvInverterAlarms.ChannelId.valueOf(this.name()));
	}

	public static long maskOf(RtsPvInverterAlarmId... alarmIds) {
		long result = 0L;
		for (var alarmId : alarmIds) {
			result |= alarmId.mask();
		}
		return result;
	}

	public static boolean isSupported(long supportedAlarmMask, RtsPvInverterAlarmId alarmId) {
		return (supportedAlarmMask & alarmId.mask()) != 0;
	}
}
