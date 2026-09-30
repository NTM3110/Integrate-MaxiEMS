package io.openems.edge.bms.service.monitoring.model;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.CRC32;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmEvaluation;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;

/**
 * Versioned Modbus input-register projection of one atomic monitoring frame.
 * Addresses are workbook-style, one-based addresses. Multi-word values use
 * network word order (most-significant word first).
 */
public final class BmsMonitoringModbusMap {

	public static final int CONTRACT_VERSION = 1;
	public static final int FIRST_ADDRESS = 6001;
	public static final int LAST_ADDRESS = 6134;
	public static final int UNAVAILABLE_WORD = 0xffff;
	public static final int UNAVAILABLE_S32 = 0x8000_0000;

	private static final int FIRST_ALARM_ADDRESS = 6061;
	private static final int FIRST_MEASUREMENT_ADDRESS = 6101;

	private BmsMonitoringModbusMap() {
	}

	/** Returns whether this address belongs to the versioned monitoring block. */
	public static boolean isMonitoringAddress(int address) {
		return address >= FIRST_ADDRESS && address <= LAST_ADDRESS;
	}

	/**
	 * Projects a complete frame. A null frame still exposes the contract version
	 * and availability flag; every other mapped value is an explicit sentinel.
	 */
	public static Map<Integer, Integer> registers(BmsMonitoringSnapshot frame) {
		var result = new LinkedHashMap<Integer, Integer>();
		result.put(6001, CONTRACT_VERSION);
		result.put(6002, frame == null ? 0 : 1);
		result.put(6003, frameQuality(frame));
		result.put(6004, frame == null ? UNAVAILABLE_WORD : currentQuality(frame.currentQuality()));
		result.put(6005, frame == null || frame.operatingMode() == null ? UNAVAILABLE_WORD
				: operatingMode(frame.operatingMode().mode()));
		result.put(6006, frame == null || frame.operatingMode() == null ? UNAVAILABLE_WORD
				: modeQuality(frame.operatingMode().quality()));
		putU32(result, 6007, frame == null ? null : Integer.toUnsignedLong(frame.alarmMask()));
		result.put(6009, frame == null || frame.currentDirectionMapping() == null ? UNAVAILABLE_WORD
				: frame.currentDirectionMapping() == BmsCurrentDirectionMapping.POSITIVE ? 0 : 1);
		result.put(6010, 0); // reserved; reads as zero for contract v1

		putUuid(result, 6011, frame == null ? null : frame.snapshotGeneration());
		putU64(result, 6019, frame == null ? null : frame.snapshotSequence());
		putU64(result, 6023, frame == null || frame.capturedAt() == null ? null : frame.capturedAt().toEpochMilli());
		putU64(result, 6027, frame == null ? null : frame.currentMeasuredAt());
		var reference = frame == null ? null : frame.reference();
		putU64(result, 6031, reference == null ? null : reference.calibrationEventId());
		putU64(result, 6035, reference == null ? null : reference.curveVersionId());
		putU64(result, 6039, reference == null ? null : reference.calibrationProcessId());
		putS32(result, 6043, reference == null ? null : reference.processAttemptNumber(), 1);
		putU64(result, 6045, reference == null ? null : reference.recoveryFloatSampleId());
		putU64(result, 6049, reference == null ? null : reference.vocSampleId());
		putU32(result, 6053, reference == null ? null : crc32(reference.contentHash()));

		for (var alarm : Alarm.values()) {
			result.put(FIRST_ALARM_ADDRESS + alarm.bit(), frame == null
					|| frame.alarmEvaluations().get(alarm) == AlarmEvaluation.UNAVAILABLE ? UNAVAILABLE_WORD
					: (frame.alarmMask() & alarm.mask()) != 0 ? 1 : 0);
		}

		var values = frame == null ? null : frame.string();
		putMeasurement(result, 0, values, BmsMonitoringSnapshot.StringMeasurements::batteryVoltageV, 100);
		putMeasurement(result, 1, values, BmsMonitoringSnapshot.StringMeasurements::midpointVoltageV, 100);
		putMeasurement(result, 2, values, BmsMonitoringSnapshot.StringMeasurements::batteryCurrentA, 100);
		putMeasurement(result, 3, values, BmsMonitoringSnapshot.StringMeasurements::batteryPowerW, 1);
		putMeasurement(result, 4, values, BmsMonitoringSnapshot.StringMeasurements::floatCurrentA, 100);
		putMeasurement(result, 5, values, BmsMonitoringSnapshot.StringMeasurements::minCellVoltageV, 1000);
		putMeasurement(result, 6, values, BmsMonitoringSnapshot.StringMeasurements::maxCellVoltageV, 1000);
		putMeasurement(result, 7, values, BmsMonitoringSnapshot.StringMeasurements::averageCellVoltageV, 1000);
		putMeasurement(result, 8, values, BmsMonitoringSnapshot.StringMeasurements::maxCellResistanceUohm, 1);
		putMeasurement(result, 9, values, BmsMonitoringSnapshot.StringMeasurements::averageCellResistanceUohm, 1);
		putMeasurement(result, 10, values, BmsMonitoringSnapshot.StringMeasurements::ambientTemperatureC, 10);
		putMeasurement(result, 11, values, BmsMonitoringSnapshot.StringMeasurements::maxCellTemperatureC, 10);
		putMeasurement(result, 12, values, BmsMonitoringSnapshot.StringMeasurements::averageCellTemperatureC, 10);
		putMeasurement(result, 13, values, BmsMonitoringSnapshot.StringMeasurements::batterySocPct, 100);
		putMeasurement(result, 14, values, BmsMonitoringSnapshot.StringMeasurements::remainingDischargeHours, 100);
		putMeasurement(result, 15, values, BmsMonitoringSnapshot.StringMeasurements::batterySohPct, 100);
		putMeasurement(result, 16, values, BmsMonitoringSnapshot.StringMeasurements::minimumCellSohPct, 100);
		return Map.copyOf(result);
	}

	private static int frameQuality(BmsMonitoringSnapshot frame) {
		if (frame == null) return UNAVAILABLE_WORD;
		if (frame.stale()) return 1;
		return frame.qualityWarning() == null ? 0 : 2;
	}

	private static int currentQuality(CurrentQuality quality) {
		if (quality == null) return UNAVAILABLE_WORD;
		return switch (quality) {
		case FRESH -> 0;
		case RETAINED -> 1;
		case MISSING -> 2;
		case COMMUNICATION_FAILED -> 3;
		case TIMESTAMP_MISSING -> 4;
		};
	}

	private static int operatingMode(OperatingMode mode) {
		if (mode == null) return UNAVAILABLE_WORD;
		return switch (mode) {
		case UNKNOWN -> 0;
		case IDLE -> 1;
		case CHARGING -> 2;
		case FLOAT -> 3;
		case DISCHARGING -> 4;
		};
	}

	private static int modeQuality(ModeQuality quality) {
		if (quality == null) return UNAVAILABLE_WORD;
		return switch (quality) {
		case VALID -> 0;
		case CANDIDATE -> 1;
		case STALE -> 2;
		case UNAVAILABLE -> 3;
		};
	}

	private static <T extends Number> void putMeasurement(Map<Integer, Integer> target, int index,
			BmsMonitoringSnapshot.StringMeasurements values,
			Function<BmsMonitoringSnapshot.StringMeasurements, T> getter, double scale) {
		putS32(target, FIRST_MEASUREMENT_ADDRESS + index * 2, values == null ? null : getter.apply(values), scale);
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

	private static void putU64(Map<Integer, Integer> target, int address, Long value) {
		if (value == null || value < 0) {
			for (var i = 0; i < 4; i++) target.put(address + i, UNAVAILABLE_WORD);
			return;
		}
		for (var i = 0; i < 4; i++) {
			var shift = (3 - i) * 16;
			target.put(address + i, (int) (value >>> shift) & 0xffff);
		}
	}

	private static void putUuid(Map<Integer, Integer> target, int address, UUID value) {
		if (value == null) {
			for (var i = 0; i < 8; i++) target.put(address + i, UNAVAILABLE_WORD);
			return;
		}
		putLongWords(target, address, value.getMostSignificantBits());
		putLongWords(target, address + 4, value.getLeastSignificantBits());
	}

	private static void putLongWords(Map<Integer, Integer> target, int address, long value) {
		for (var i = 0; i < 4; i++) {
			var shift = (3 - i) * 16;
			target.put(address + i, (int) (value >>> shift) & 0xffff);
		}
	}

	private static Long crc32(String value) {
		if (value == null || value.isBlank()) return null;
		var crc = new CRC32();
		crc.update(value.getBytes(StandardCharsets.UTF_8));
		return crc.getValue();
	}
}
