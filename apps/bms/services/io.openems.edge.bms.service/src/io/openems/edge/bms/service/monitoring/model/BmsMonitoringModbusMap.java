package io.openems.edge.bms.service.monitoring.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmEvaluation;

/**
 * Versioned Modbus input-register projection of one atomic monitoring frame.
 * Map keys are one-based addresses relative to a string block. The process
 * image adds {@code stringId * STRING_STRIDE}; multi-word values use network
 * word order (most-significant word first).
 */
public final class BmsMonitoringModbusMap {

	public static final int CONTRACT_VERSION = 3;
	public static final int FIRST_RELATIVE_ADDRESS = 1;
	public static final int LAST_RELATIVE_ADDRESS = 36;
	public static final int ALARM_ACTIVE_MASK_ADDRESS = 35;
	public static final int ALARM_VALIDITY_MASK_ADDRESS = 36;
	public static final int STRING_STRIDE = 1_000;
	public static final int MAX_STRING_INDEX = 10;
	public static final int UNAVAILABLE_WORD = 0xffff;
	public static final int UNAVAILABLE_S32 = 0x8000_0000;

	/** Register representation used by the stable monitoring contract. */
	public enum RegisterValueType {
		BITMASK_16, SIGNED_32
	}

	/** One stable alarm bit shared by the active and validity masks. */
	public record AlarmBit(int bit, Alarm alarm, String label) {
		public RegisterValueType type() {
			return RegisterValueType.BITMASK_16;
		}

		public int mask() {
			return 1 << this.bit;
		}
	}

	/**
	 * Ordered string-level measurement catalog. Each value occupies two registers
	 * as a signed 32-bit integer in network word order.
	 */
	public enum MeasurementRegister {
		BATTERY_VOLTAGE(1, "Battery Voltage", "V", 100,
				BmsMonitoringSnapshot.StringMeasurements::batteryVoltageV),
		MIDPOINT_VOLTAGE(3, "Midpoint Voltage", "V", 100,
				BmsMonitoringSnapshot.StringMeasurements::midpointVoltageV),
		BATTERY_CURRENT(5, "Battery Current", "A", 100,
				BmsMonitoringSnapshot.StringMeasurements::batteryCurrentA),
		BATTERY_POWER(7, "Battery Power", "W", 1,
				BmsMonitoringSnapshot.StringMeasurements::batteryPowerW),
		FLOAT_CURRENT(9, "Float Current", "A", 100,
				BmsMonitoringSnapshot.StringMeasurements::floatCurrentA),
		MIN_CELL_VOLTAGE(11, "Min Cell Voltage", "V", 1000,
				BmsMonitoringSnapshot.StringMeasurements::minCellVoltageV),
		MAX_CELL_VOLTAGE(13, "Max Cell Voltage", "V", 1000,
				BmsMonitoringSnapshot.StringMeasurements::maxCellVoltageV),
		AVERAGE_CELL_VOLTAGE(15, "Average Cell Voltage", "V", 1000,
				BmsMonitoringSnapshot.StringMeasurements::averageCellVoltageV),
		MAX_CELL_RESISTANCE(17, "Max Cell Resistance", "uOhm", 1,
				BmsMonitoringSnapshot.StringMeasurements::maxCellResistanceUohm),
		AVERAGE_CELL_RESISTANCE(19, "Average Cell Resistance", "uOhm", 1,
				BmsMonitoringSnapshot.StringMeasurements::averageCellResistanceUohm),
		AMBIENT_TEMPERATURE(21, "Ambient Temperature", "degC", 10,
				BmsMonitoringSnapshot.StringMeasurements::ambientTemperatureC),
		MAX_CELL_TEMPERATURE(23, "Max Cell Temperature", "degC", 10,
				BmsMonitoringSnapshot.StringMeasurements::maxCellTemperatureC),
		AVERAGE_CELL_TEMPERATURE(25, "Average Cell Temperature", "degC", 10,
				BmsMonitoringSnapshot.StringMeasurements::averageCellTemperatureC),
		BATTERY_SOC(27, "Battery SOC", "%", 100,
				BmsMonitoringSnapshot.StringMeasurements::batterySocPct),
		REMAINING_DISCHARGE_TIME(29, "Remaining Discharge Time", "h", 100,
				BmsMonitoringSnapshot.StringMeasurements::remainingDischargeHours),
		BATTERY_SOH(31, "Battery SOH", "%", 100,
				BmsMonitoringSnapshot.StringMeasurements::batterySohPct),
		MINIMUM_CELL_SOH(33, "Minimum Cell SOH", "%", 100,
				BmsMonitoringSnapshot.StringMeasurements::minimumCellSohPct);

		private final int address;
		private final String label;
		private final String unit;
		private final int scale;
		private final Function<BmsMonitoringSnapshot.StringMeasurements, ? extends Number> getter;

		MeasurementRegister(int address, String label, String unit, int scale,
				Function<BmsMonitoringSnapshot.StringMeasurements, ? extends Number> getter) {
			this.address = address;
			this.label = label;
			this.unit = unit;
			this.scale = scale;
			this.getter = getter;
		}

		public int address() {
			return this.address;
		}

		public int width() {
			return 2;
		}

		public RegisterValueType type() {
			return RegisterValueType.SIGNED_32;
		}

		public String label() {
			return this.label;
		}

		public String unit() {
			return this.unit;
		}

		public int scale() {
			return this.scale;
		}

		private Number value(BmsMonitoringSnapshot.StringMeasurements values) {
			return this.getter.apply(values);
		}
	}

	private static final List<AlarmBit> ALARM_BITS = List.of(
			new AlarmBit(0, Alarm.LOW_CELL_VOLTAGE, "Low Cell Voltage Alarm"),
			new AlarmBit(1, Alarm.HIGH_CELL_VOLTAGE, "High Cell Voltage Alarm"),
			new AlarmBit(2, Alarm.BATTERY_VOLTAGE_LOW, "Battery Voltage Low"),
			new AlarmBit(3, Alarm.BATTERY_VOLTAGE_HIGH, "Battery Voltage High"),
			new AlarmBit(4, Alarm.CELL_RESISTANCE, "Cell Resistance Alarm"),
			new AlarmBit(5, Alarm.CELL_TEMPERATURE, "Cell Temperature Alarm"),
			new AlarmBit(6, Alarm.FLOAT_CHARGE_OVERCURRENT, "Float Charge Overcurrent Alarm"),
			new AlarmBit(7, Alarm.CHARGE_OVERCURRENT, "Charge Overcurrent Alarm"),
			new AlarmBit(8, Alarm.LOW_SOC, "Low SOC Alarm"),
			new AlarmBit(9, Alarm.HIGH_AMBIENT_TEMPERATURE, "High Ambient Temperature"),
			new AlarmBit(10, Alarm.MIDPOINT_VOLTAGE, "Midpoint Voltage Alarm"),
			new AlarmBit(11, Alarm.SENSOR_MODULE_FAILURE, "Sensor/Module Failure"));

	private BmsMonitoringModbusMap() {
	}

	/** Ordered alarm-bit contract shared by both alarm masks. */
	public static List<AlarmBit> alarmBits() {
		return ALARM_BITS;
	}

	/** Ordered measurement-register contract. */
	public static List<MeasurementRegister> measurementRegisters() {
		return List.of(MeasurementRegister.values());
	}

	/** Resolves a zero-based protocol offset to its string ID. */
	public static int stringIndexForOffset(int offset) {
		if (offset < STRING_STRIDE) return -1;
		var stringIndex = offset / STRING_STRIDE;
		return stringIndex <= MAX_STRING_INDEX ? stringIndex : -1;
	}

	/** Returns whether this relative address belongs to the monitoring block. */
	public static boolean isMonitoringAddress(int relativeAddress) {
		return relativeAddress >= FIRST_RELATIVE_ADDRESS && relativeAddress <= LAST_RELATIVE_ADDRESS;
	}

	/**
	 * Projects all 36 relative registers from one frame. A null frame exposes an
	 * explicit sentinel for every measurement and zero for both alarm masks.
	 */
	public static Map<Integer, Integer> registers(BmsMonitoringSnapshot frame) {
		var result = new LinkedHashMap<Integer, Integer>();
		var values = frame == null ? null : frame.string();
		for (var register : MeasurementRegister.values()) {
			putS32(result, register.address(), values == null ? null : register.value(values), register.scale());
		}
		var activeMask = 0;
		var validityMask = 0;
		if (frame != null) {
			for (var alarmBit : ALARM_BITS) {
				var alarm = alarmBit.alarm();
				if (frame.alarmEvaluations().get(alarm) != AlarmEvaluation.AVAILABLE) {
					continue;
				}
				validityMask |= alarmBit.mask();
				if ((frame.alarmMask() & alarm.mask()) != 0) {
					activeMask |= alarmBit.mask();
				}
			}
		}
		result.put(ALARM_ACTIVE_MASK_ADDRESS, activeMask);
		result.put(ALARM_VALIDITY_MASK_ADDRESS, validityMask);
		return Map.copyOf(result);
	}

	private static void putS32(Map<Integer, Integer> target, int address, Number value, double scale) {
		long scaled = UNAVAILABLE_S32;
		if (value != null && Double.isFinite(value.doubleValue())) {
			var candidate = Math.round(value.doubleValue() * scale);
			if (candidate >= Integer.MIN_VALUE && candidate <= Integer.MAX_VALUE && candidate != UNAVAILABLE_S32) {
				scaled = candidate;
			}
		}
		putU32(target, address, scaled & 0xffff_ffffL);
	}

	private static void putU32(Map<Integer, Integer> target, int address, Long value) {
		if (value == null || value < 0 || value > 0xffff_ffffL) {
			target.put(address, UNAVAILABLE_WORD);
			target.put(address + 1, UNAVAILABLE_WORD);
			return;
		}
		target.put(address, (int) (value >>> 16) & 0xffff);
		target.put(address + 1, value.intValue() & 0xffff);
	}

}
