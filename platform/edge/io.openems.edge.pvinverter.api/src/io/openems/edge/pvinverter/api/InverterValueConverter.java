package io.openems.edge.pvinverter.api;

import io.openems.common.exceptions.OpenemsException;

public final class InverterValueConverter {

	public enum PowerSignConvention {
		BATTERY_DISCHARGE_POSITIVE,
		BATTERY_CHARGE_POSITIVE,
		GRID_IMPORT_POSITIVE,
		GRID_EXPORT_POSITIVE;
	}

	private InverterValueConverter() {
	}

	public static int kilowattsToWatts(double value) throws OpenemsException {
		return scaleToInteger("kilowatts", value, 1_000, false);
	}

	public static int voltsToMillivolts(double value) throws OpenemsException {
		return scaleToInteger("volts", value, 1_000, true);
	}

	public static int ampsToMilliamps(double value) throws OpenemsException {
		return scaleToInteger("amps", value, 1_000, true);
	}

	public static int requireNonNegative(String name, int value) throws OpenemsException {
		if (value < 0) {
			throw new OpenemsException(name + " must be non-negative");
		}
		return value;
	}

	public static int rejectSentinel(String name, int value, int sentinel) throws OpenemsException {
		if (value == sentinel) {
			throw new OpenemsException(name + " is sentinel value: " + sentinel);
		}
		return value;
	}

	public static int normalizeBatteryPower(int value, PowerSignConvention convention) throws OpenemsException {
		return switch (convention) {
		case BATTERY_DISCHARGE_POSITIVE -> value;
		case BATTERY_CHARGE_POSITIVE -> negatePower("batteryPower", value, convention);
		case GRID_IMPORT_POSITIVE, GRID_EXPORT_POSITIVE -> throw new OpenemsException(
				"Battery power requires a battery sign convention");
		};
	}

	public static int normalizeGridPower(int value, PowerSignConvention convention) throws OpenemsException {
		return switch (convention) {
		case GRID_IMPORT_POSITIVE -> value;
		case GRID_EXPORT_POSITIVE -> negatePower("gridPower", value, convention);
		case BATTERY_DISCHARGE_POSITIVE, BATTERY_CHARGE_POSITIVE -> throw new OpenemsException(
				"Grid power requires a grid sign convention");
		};
	}

	private static int negatePower(String name, int value, PowerSignConvention convention) throws OpenemsException {
		try {
			return Math.negateExact(value);
		} catch (ArithmeticException e) {
			throw new OpenemsException(name + " overflows while applying " + convention.name(), e);
		}
	}

	public static int requirePercent(String name, int value) throws OpenemsException {
		return requireRange(name, value, 0, 100);
	}

	public static void requireWriteEnabled(boolean writeEnabled) throws OpenemsException {
		if (!writeEnabled) {
			throw new OpenemsException("Writes are disabled");
		}
	}

	public static int requireRange(String name, int value, int min, int max) throws OpenemsException {
		if (min > max) {
			throw new OpenemsException(name + " has invalid range");
		}
		if (value < min || value > max) {
			throw new OpenemsException(name + " out of range " + min + ".." + max + ": " + value);
		}
		return value;
	}

	public static SystemCommand requireSystemCommand(int value) throws OpenemsException {
		for (var command : SystemCommand.values()) {
			if (command != SystemCommand.UNDEFINED && command.getValue() == value) {
				return command;
			}
		}
		throw new OpenemsException("Unsupported system command: " + value);
	}

	private static int scaleToInteger(String name, double value, int factor, boolean nonNegative) throws OpenemsException {
		if (!Double.isFinite(value)) {
			throw new OpenemsException(name + " must be finite");
		}
		if (nonNegative && value < 0) {
			throw new OpenemsException(name + " must be non-negative");
		}
		var scaled = Math.round(value * factor);
		if (scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE) {
			throw new OpenemsException(name + " overflows integer range");
		}
		return (int) scaled;
	}
}
