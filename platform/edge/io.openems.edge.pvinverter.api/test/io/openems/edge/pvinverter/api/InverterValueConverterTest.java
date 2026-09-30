package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import io.openems.common.exceptions.OpenemsException;

public class InverterValueConverterTest {

	@Test
	public void convertsWorkbookUnitsToOpenemsUnits() throws Exception {
		assertEquals(12_300, InverterValueConverter.kilowattsToWatts(12.3));
		assertEquals(-4_500, InverterValueConverter.kilowattsToWatts(-4.5));
		assertEquals(52_300, InverterValueConverter.voltsToMillivolts(52.3));
		assertEquals(17_400, InverterValueConverter.ampsToMilliamps(17.4));
	}

	@Test
	public void rejectsNegativeMagnitudeAndOverflow() {
		assertThrows(OpenemsException.class,
				() -> InverterValueConverter.requireNonNegative("chargeCurrentLimit", -1));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.kilowattsToWatts(Double.MAX_VALUE));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.voltsToMillivolts(-0.1));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.ampsToMilliamps(Double.NaN));
	}

	@Test
	public void writeGateRejectsWhenDisabled() {
		assertThrows(OpenemsException.class, () -> InverterValueConverter.requireWriteEnabled(false));
	}

	@Test
	public void validatesRangesAndEnums() throws Exception {
		assertEquals(42, InverterValueConverter.requireRange("emsMode", 42, 0, 100));
		assertEquals(SystemCommand.START, InverterValueConverter.requireSystemCommand(1));
		assertEquals(SystemCommand.STOP, InverterValueConverter.requireSystemCommand(0));

		assertThrows(OpenemsException.class, () -> InverterValueConverter.requireRange("reserveSoc", 101, 0, 100));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.requireSystemCommand(-1));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.requireSystemCommand(2));
	}

	@Test
	public void rejectsConfiguredIntegerSentinelWhilePreservingLegitimateValues() throws Exception {
		assertEquals(-4_500, InverterValueConverter.rejectSentinel("batteryPower", -4_500, Short.MIN_VALUE));
		assertEquals(0, InverterValueConverter.rejectSentinel("batteryPower", 0, Short.MIN_VALUE));
		assertEquals(4_500, InverterValueConverter.rejectSentinel("batteryPower", 4_500, Short.MIN_VALUE));

		assertThrows(OpenemsException.class,
				() -> InverterValueConverter.rejectSentinel("batteryPower", Short.MIN_VALUE, Short.MIN_VALUE));
	}

	@Test
	public void normalizesSignedPowerUsingExplicitVendorConvention() throws Exception {
		assertEquals(500, InverterValueConverter.normalizeBatteryPower(500,
				InverterValueConverter.PowerSignConvention.BATTERY_DISCHARGE_POSITIVE));
		assertEquals(-500, InverterValueConverter.normalizeBatteryPower(500,
				InverterValueConverter.PowerSignConvention.BATTERY_CHARGE_POSITIVE));
		assertEquals(700, InverterValueConverter.normalizeGridPower(700,
				InverterValueConverter.PowerSignConvention.GRID_IMPORT_POSITIVE));
		assertEquals(-700, InverterValueConverter.normalizeGridPower(700,
				InverterValueConverter.PowerSignConvention.GRID_EXPORT_POSITIVE));
	}

	@Test
	public void rejectsOverflowWhenSignFlipWouldNegateIntegerMinValue() {
		assertThrows(OpenemsException.class, () -> InverterValueConverter.normalizeBatteryPower(Integer.MIN_VALUE,
				InverterValueConverter.PowerSignConvention.BATTERY_CHARGE_POSITIVE));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.normalizeGridPower(Integer.MIN_VALUE,
				InverterValueConverter.PowerSignConvention.GRID_EXPORT_POSITIVE));
	}

	@Test
	public void rejectsInvalidConventionFamiliesAsOpenemsException() {
		assertThrows(OpenemsException.class, () -> InverterValueConverter.normalizeBatteryPower(1,
				InverterValueConverter.PowerSignConvention.GRID_IMPORT_POSITIVE));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.normalizeGridPower(1,
				InverterValueConverter.PowerSignConvention.BATTERY_DISCHARGE_POSITIVE));
	}

	@Test
	public void validatesPercentBoundaries() throws Exception {
		assertEquals(0, InverterValueConverter.requirePercent("reserveSoc", 0));
		assertEquals(100, InverterValueConverter.requirePercent("reserveSoc", 100));

		assertThrows(OpenemsException.class, () -> InverterValueConverter.requirePercent("reserveSoc", -1));
		assertThrows(OpenemsException.class, () -> InverterValueConverter.requirePercent("reserveSoc", 101));
	}
}
