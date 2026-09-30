package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Stream;

import org.junit.Test;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.meter.api.ElectricityMeter;

public class HybridInverterChannelTest {

	@Test
	public void testHybridInverterChannelMetadata() {
		assertEquals(28, HybridInverter.ChannelId.values().length);
		assertIntegerRead(Unit.NONE, HybridInverter.ChannelId.OPERATING_STATE, HybridInverter.ChannelId.RUN_MODE,
				HybridInverter.ChannelId.BATTERY_STATE);
		assertIntegerRead(Unit.WATT, HybridInverter.ChannelId.GRID_ACTIVE_POWER,
				HybridInverter.ChannelId.GRID_ACTIVE_POWER_L1, HybridInverter.ChannelId.GRID_ACTIVE_POWER_L2,
				HybridInverter.ChannelId.GRID_ACTIVE_POWER_L3, HybridInverter.ChannelId.BATTERY_POWER,
				HybridInverter.ChannelId.LOAD_ACTIVE_POWER, HybridInverter.ChannelId.EPS_ACTIVE_POWER,
				HybridInverter.ChannelId.GENERATOR_ACTIVE_POWER);
		assertIntegerRead(Unit.PERCENT, HybridInverter.ChannelId.BATTERY_SOC);
		assertIntegerRead(Unit.MILLIVOLT, HybridInverter.ChannelId.BATTERY_VOLTAGE,
				HybridInverter.ChannelId.BATTERY_CHARGE_VOLTAGE_LIMIT,
				HybridInverter.ChannelId.BATTERY_DISCHARGE_VOLTAGE_LIMIT,
				HybridInverter.ChannelId.BATTERY_CHARGE_VOLTAGE,
				HybridInverter.ChannelId.BATTERY_DISCHARGE_VOLTAGE);
		assertIntegerRead(Unit.MILLIAMPERE, HybridInverter.ChannelId.BATTERY_CURRENT,
				HybridInverter.ChannelId.BATTERY_CHARGE_CURRENT,
				HybridInverter.ChannelId.BATTERY_DISCHARGE_CURRENT);
		assertIntegerRead(Unit.AMPERE_HOURS, HybridInverter.ChannelId.BATTERY_CAPACITY);
		assertChannel(HybridInverter.ChannelId.BATTERY_TEMPURATURE, OpenemsType.FLOAT, Unit.DEGREE_CELSIUS,
				AccessMode.READ_ONLY, PersistencePriority.MEDIUM);
		assertIntegerRead(Unit.MILLIHERTZ, HybridInverter.ChannelId.EPS_FREQUENCY,
				HybridInverter.ChannelId.GENERATOR_FREQUENCY);
		assertLongRead(Unit.CUMULATED_WATT_HOURS, HybridInverter.ChannelId.GRID_IMPORT_ENERGY,
				HybridInverter.ChannelId.GRID_EXPORT_ENERGY, HybridInverter.ChannelId.BATTERY_CHARGE_ENERGY,
				HybridInverter.ChannelId.BATTERY_DISCHARGE_ENERGY);
	}

	@Test
	public void testHybridInverterControlChannelMetadata() {
		assertEquals(19, HybridInverterControl.ChannelId.values().length);
		assertChannel(HybridInverterControl.ChannelId.WRITE_ENABLED, OpenemsType.BOOLEAN, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.HIGH);
		assertChannel(HybridInverterControl.ChannelId.LAST_COMMAND_RESULT, OpenemsType.INTEGER, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.HIGH);
		assertChannel(HybridInverterControl.ChannelId.LAST_COMMAND_REJECTION_REASON, OpenemsType.STRING, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.HIGH);
		assertIntegerWrite(Unit.NONE, HybridInverterControl.ChannelId.EMS_MODE_REQUEST);
		assertIntegerRead(Unit.NONE, HybridInverterControl.ChannelId.EMS_MODE_READBACK);
		assertIntegerWrite(Unit.THOUSANDTH, HybridInverterControl.ChannelId.BATTERY_POWER_REFERENCE_REQUEST);
		assertIntegerRead(Unit.THOUSANDTH, HybridInverterControl.ChannelId.BATTERY_POWER_REFERENCE_READBACK);
		assertIntegerWrite(Unit.WATT, HybridInverterControl.ChannelId.ACTIVE_POWER_LIMIT_REQUEST);
		assertIntegerRead(Unit.WATT, HybridInverterControl.ChannelId.ACTIVE_POWER_LIMIT_READBACK);
		assertIntegerWrite(Unit.PERCENT, HybridInverterControl.ChannelId.RESERVE_SOC_REQUEST);
		assertIntegerRead(Unit.PERCENT, HybridInverterControl.ChannelId.RESERVE_SOC_READBACK);
		assertIntegerWrite(Unit.MILLIAMPERE, HybridInverterControl.ChannelId.CHARGE_CURRENT_LIMIT_REQUEST,
				HybridInverterControl.ChannelId.DISCHARGE_CURRENT_LIMIT_REQUEST);
		assertIntegerRead(Unit.MILLIAMPERE, HybridInverterControl.ChannelId.CHARGE_CURRENT_LIMIT_READBACK,
				HybridInverterControl.ChannelId.DISCHARGE_CURRENT_LIMIT_READBACK);
		assertChannel(HybridInverterControl.ChannelId.SYSTEM_COMMAND_REQUEST, OpenemsType.INTEGER, Unit.NONE,
				AccessMode.WRITE_ONLY, PersistencePriority.HIGH);
		assertChannel(HybridInverterControl.ChannelId.SYSTEM_COMMAND_READBACK, OpenemsType.INTEGER, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.HIGH);
		assertChannel(HybridInverterControl.ChannelId.GRID_CHARGE_ENABLE_REQUEST, OpenemsType.BOOLEAN, Unit.NONE,
				AccessMode.WRITE_ONLY, PersistencePriority.HIGH);
		assertChannel(HybridInverterControl.ChannelId.GRID_CHARGE_ENABLE_READBACK, OpenemsType.BOOLEAN, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.HIGH);
	}

	@Test
	public void testHybridInverterAlarmChannelMetadata() {
		assertEquals(2, HybridInverterAlarms.ChannelId.values().length);
		assertChannel(HybridInverterAlarms.ChannelId.SUPPORTED_ALARM_MASK, OpenemsType.LONG, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.MEDIUM);
		assertChannel(HybridInverterAlarms.ChannelId.ALARM_DATA_VALID, OpenemsType.BOOLEAN, Unit.NONE,
				AccessMode.READ_ONLY, PersistencePriority.HIGH);
	}

	@Test
	public void testNoDuplicateRuntimeIdsAcrossSharedNatures() {
		var ids = new HashSet<String>();
		Stream.of(HybridInverter.ChannelId.values(), HybridInverterControl.ChannelId.values(),
				HybridInverterAlarms.ChannelId.values(), ElectricityMeter.ChannelId.values(), RtsPvInverter.ChannelId.values())
				.flatMap(Stream::of)
				.map(ChannelId::id)
				.forEach(id -> assertTrue("Duplicate ChannelId runtime id: " + id, ids.add(id)));
	}

	private static void assertChannel(ChannelId channelId, OpenemsType type, Unit unit, AccessMode accessMode,
			PersistencePriority priority) {
		assertEquals(type, channelId.doc().getType());
		assertEquals(unit, channelId.doc().getUnit());
		assertEquals(accessMode, channelId.doc().getAccessMode());
		assertEquals(priority, channelId.doc().getPersistencePriority());
	}

	private static void assertIntegerRead(Unit unit, ChannelId... channelIds) {
		Arrays.stream(channelIds).forEach(channelId -> assertChannel(channelId, OpenemsType.INTEGER, unit,
				AccessMode.READ_ONLY, PersistencePriority.HIGH));
	}

	private static void assertIntegerWrite(Unit unit, ChannelId... channelIds) {
		Arrays.stream(channelIds).forEach(channelId -> assertChannel(channelId, OpenemsType.INTEGER, unit,
				AccessMode.WRITE_ONLY, PersistencePriority.HIGH));
	}

	private static void assertLongRead(Unit unit, ChannelId... channelIds) {
		Arrays.stream(channelIds).forEach(channelId -> assertChannel(channelId, OpenemsType.LONG, unit,
				AccessMode.READ_ONLY, PersistencePriority.HIGH));
	}
}
