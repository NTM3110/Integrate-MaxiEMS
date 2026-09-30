package io.openems.edge.alarm.api;

import io.openems.common.channel.Level;

/** Stable semantic alarm identifiers and capability-mask bits. */
public enum AlarmId {
	COMMUNICATION_LOST(0, AlarmProfile.DEVICE, Level.WARNING, "Communication Lost"),
	DEVICE_OFFLINE(1, AlarmProfile.DEVICE, Level.WARNING, "Device Offline"),
	INVALID_DATA(2, AlarmProfile.DEVICE, Level.WARNING, "Invalid Data"),
	CONFIGURATION_ERROR(3, AlarmProfile.DEVICE, Level.FAULT, "Configuration Error"),
	UNSUPPORTED_DEVICE(4, AlarmProfile.DEVICE, Level.FAULT, "Unsupported Device"),

	GRID_OVER_VOLTAGE(5, AlarmProfile.INVERTER, Level.FAULT, "Grid Over Voltage"),
	GRID_UNDER_VOLTAGE(6, AlarmProfile.INVERTER, Level.FAULT, "Grid Under Voltage"),
	GRID_OVER_FREQUENCY(7, AlarmProfile.INVERTER, Level.FAULT, "Grid Over Frequency"),
	GRID_UNDER_FREQUENCY(8, AlarmProfile.INVERTER, Level.FAULT, "Grid Under Frequency"),
	GRID_VOLTAGE_IMBALANCE(9, AlarmProfile.INVERTER, Level.WARNING, "Grid Voltage Imbalance"),
	GRID_FREQUENCY_ABNORMAL(10, AlarmProfile.INVERTER, Level.FAULT, "Grid Frequency Abnormal"),
	GRID_PHASE_LOSS(11, AlarmProfile.INVERTER, Level.FAULT, "Grid Phase Loss"),
	GRID_PHASE_SEQUENCE_ERROR(12, AlarmProfile.INVERTER, Level.FAULT, "Grid Phase Sequence Error"),
	GRID_CONNECTION_FAILURE(13, AlarmProfile.INVERTER, Level.FAULT, "Grid Connection Failure"),
	GRID_RECONNECT_FAILURE(14, AlarmProfile.INVERTER, Level.FAULT, "Grid Reconnect Failure"),
	REVERSE_POWER_DETECTED(15, AlarmProfile.INVERTER, Level.WARNING, "Reverse Power Detected"),
	WEAK_GRID_DETECTED(16, AlarmProfile.INVERTER, Level.WARNING, "Weak Grid Detected"),
	GRID_FAULT(17, AlarmProfile.INVERTER, Level.FAULT, "Grid Fault"),
	DC_OVER_VOLTAGE(18, AlarmProfile.INVERTER, Level.FAULT, "DC Over Voltage"),
	DC_UNDER_VOLTAGE(19, AlarmProfile.INVERTER, Level.WARNING, "DC Under Voltage"),
	DC_OVER_CURRENT(20, AlarmProfile.INVERTER, Level.FAULT, "DC Over Current"),
	DC_REVERSE_POLARITY(21, AlarmProfile.INVERTER, Level.FAULT, "DC Reverse Polarity"),
	PV_ISOLATION_FAULT(22, AlarmProfile.INVERTER, Level.FAULT, "PV Isolation Fault"),
	GROUND_FAULT(23, AlarmProfile.INVERTER, Level.FAULT, "Ground Fault"),
	ARC_FAULT(24, AlarmProfile.INVERTER, Level.FAULT, "Arc Fault"),
	LEAKAGE_CURRENT_HIGH(25, AlarmProfile.INVERTER, Level.FAULT, "Leakage Current High"),
	INSULATION_RESISTANCE_LOW(26, AlarmProfile.INVERTER, Level.FAULT, "Insulation Resistance Low"),
	PV_STRING_FAULT(27, AlarmProfile.INVERTER, Level.WARNING, "PV String Fault"),
	PV_INPUT_FAILURE(28, AlarmProfile.INVERTER, Level.WARNING, "PV Input Failure"),
	DC_FAULT(29, AlarmProfile.INVERTER, Level.FAULT, "DC Fault"),

	LOGGER_INTERNAL_FAULT(32, AlarmProfile.LOGGER, Level.FAULT, "Logger Internal Fault"),
	LOGGER_STORAGE_FAULT(33, AlarmProfile.LOGGER, Level.FAULT, "Logger Storage Fault"),
	LOGGER_TIME_SYNC_FAULT(34, AlarmProfile.LOGGER, Level.WARNING, "Logger Time Sync Fault"),
	LOGGER_NETWORK_FAULT(35, AlarmProfile.LOGGER, Level.WARNING, "Logger Network Fault"),
	DATA_FORWARDING_FAULT(36, AlarmProfile.LOGGER, Level.WARNING, "Data Forwarding Fault"),
	DOWNSTREAM_COMMUNICATION_FAULT(37, AlarmProfile.LOGGER, Level.WARNING,
			"Downstream Communication Fault");

	public static final long DEVICE_MASK = maskOfProfile(AlarmProfile.DEVICE);
	public static final long INVERTER_MASK = maskOfProfile(AlarmProfile.INVERTER);
	public static final long LOGGER_MASK = maskOfProfile(AlarmProfile.LOGGER);
	public static final long ALL_MASK = DEVICE_MASK | INVERTER_MASK | LOGGER_MASK;

	private final int bit;
	private final AlarmProfile profile;
	private final Level level;
	private final String text;

	private AlarmId(int bit, AlarmProfile profile, Level level, String text) {
		this.bit = bit;
		this.profile = profile;
		this.level = level;
		this.text = text;
	}

	public int bit() {
		return this.bit;
	}

	public long mask() {
		return 1L << this.bit;
	}

	public AlarmProfile profile() {
		return this.profile;
	}

	public Level level() {
		return this.level;
	}

	public String text() {
		return this.text;
	}

	/** Returns true if the current value originates from device fault data. */
	public boolean isDeviceDerived() {
		return this == DEVICE_OFFLINE || this.profile != AlarmProfile.DEVICE;
	}

	public static long maskOf(AlarmId... alarmIds) {
		long result = 0L;
		for (var alarmId : alarmIds) {
			result |= alarmId.mask();
		}
		return result;
	}

	public static boolean isSupported(long supportedAlarmMask, AlarmId alarmId) {
		return (supportedAlarmMask & alarmId.mask()) != 0;
	}

	private static long maskOfProfile(AlarmProfile profile) {
		long result = 0L;
		for (var alarmId : values()) {
			if (alarmId.profile == profile) {
				result |= alarmId.mask();
			}
		}
		return result;
	}
}
