package io.openems.edge.bms.controllers.modbus;

import java.util.Map;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;

/**
 * Fixed register table from the BMS Modbus server workbook. Addresses accepted
 * by this class are Modbus protocol offsets (the workbook's 1-based address
 * minus one). Values are unsigned words; {@value #UNAVAILABLE} means missing.
 */
public final class BmsModbusRegisterMapper {
	public static final int UNAVAILABLE = 0xffff;
	public static final int STRING_STRIDE = 10_000;
	private static final int CELL_COUNT = 110;

	private BmsModbusRegisterMapper() {
	}

	public static int stringIndexForOffset(int offset) {
		return offset < 0 ? -1 : offset / STRING_STRIDE + 1;
	}

	public static int registerFor(BmsCalculationSnapshot snapshot, int blockOffset) {
		var address = blockOffset + 1; // Workbook address.
		if (address >= 1001 && address <= 1110) {
			return cell(snapshot, address - 1000, BmsCalculationSnapshot.Cell::internalResistance, 1);
		}
		if (address >= 1301 && address <= 1410) {
			return cell(snapshot, address - 1300, BmsCalculationSnapshot.Cell::voltage, 1000);
		}
		if (address >= 1601 && address <= 1710) {
			return cell(snapshot, address - 1600, BmsCalculationSnapshot.Cell::temperature, 10);
		}
		if (address >= 1901 && address <= 2010) {
			return cell(snapshot, address - 1900, BmsCalculationSnapshot.Cell::soc, 1);
		}
		if (address >= 2201 && address <= 2310) {
			return cell(snapshot, address - 2200, BmsCalculationSnapshot.Cell::soh, 1);
		}
		return aggregate(snapshot, address);
	}

	private static int aggregate(BmsCalculationSnapshot snapshot, int address) {
		return switch (address) {
		case 3001 -> scaled(snapshot.stringCurrent(), 10); // signed two's-complement word
		case 3101 -> scaled(snapshot.ambientTemperature(), 10);
		case 3201 -> scaled(snapshot.stringSoc(), 10);
		case 3301 -> scaled(snapshot.stringSoh(), 10);
		case 3401 -> scaled(snapshot.stringVoltage(), 1000);
		case 3501 -> integer(snapshot.maxVoltageCellIndex());
		case 3601 -> integer(snapshot.minVoltageCellIndex());
		case 3701 -> integer(snapshot.maxTemperatureCellIndex());
		case 3801 -> integer(snapshot.minTemperatureCellIndex());
		case 3901 -> integer(snapshot.maxResistanceCellIndex());
		case 4001 -> integer(snapshot.minResistanceCellIndex());
		case 4101 -> scaled(snapshot.averageVoltage(), 1000);
		case 4201 -> scaled(snapshot.averageTemperature(), 10);
		case 4301 -> scaled(snapshot.averageResistance(), 1);
		case 4401 -> scaled(snapshot.maxVoltage(), 1000);
		case 4501 -> scaled(snapshot.minVoltage(), 1000);
		case 4601 -> scaled(snapshot.maxTemperature(), 10);
		case 4701 -> scaled(snapshot.minTemperature(), 10);
		case 4801 -> integer(snapshot.maxResistance());
		case 4901 -> integer(snapshot.minResistance());
		case 5001 -> alarm(snapshot);
		default -> throw new IllegalArgumentException("Unsupported BMS Modbus address " + address);
		};
	}

	private static <T extends Number> int cell(BmsCalculationSnapshot snapshot, int cellId,
			java.util.function.Function<BmsCalculationSnapshot.Cell, T> value, double scale) {
		if (cellId < 1 || cellId > CELL_COUNT) {
			throw new IllegalArgumentException("Unsupported BMS cell " + cellId);
		}
		var cell = snapshot.cells().get(cellId);
		return cell == null ? UNAVAILABLE : scaled(value.apply(cell), scale);
	}

	private static int alarm(BmsCalculationSnapshot snapshot) {
		if (snapshot.stringWarning() != null) return 1;
		return snapshot.cells().values().stream().anyMatch(cell -> cell.availabilityWarning() != null) ? 1 : 0;
	}

	private static int integer(Integer value) {
		return value == null ? UNAVAILABLE : word(value.longValue());
	}

	private static int scaled(Number value, double scale) {
		if (value == null || !Double.isFinite(value.doubleValue())) return UNAVAILABLE;
		return word(Math.round(value.doubleValue() * scale));
	}

	private static int word(long value) {
		if (value < Short.MIN_VALUE || value > UNAVAILABLE) {
			return UNAVAILABLE;
		}
		var word = (int) value & UNAVAILABLE;
		/* 0xFFFF is reserved exclusively for unavailable values. */
		return word == UNAVAILABLE ? UNAVAILABLE : word;
	}

	public static BmsCalculationSnapshot snapshot(Map<Integer, BmsCalculationSnapshot> snapshots, int stringIndex) {
		return snapshots.get(stringIndex);
	}
}
