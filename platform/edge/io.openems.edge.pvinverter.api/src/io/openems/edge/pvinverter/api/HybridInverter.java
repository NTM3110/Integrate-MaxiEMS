package io.openems.edge.pvinverter.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

public interface HybridInverter extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		OPERATING_STATE(integer()),
		RUN_MODE(integer()),
		GRID_ACTIVE_POWER(watt()),
		GRID_ACTIVE_POWER_L1(watt()),
		GRID_ACTIVE_POWER_L2(watt()),
		GRID_ACTIVE_POWER_L3(watt()),
		BATTERY_STATE(integer()),
		BATTERY_SOC(integer(Unit.PERCENT)),
		BATTERY_VOLTAGE(integer(Unit.MILLIVOLT)),
		BATTERY_CURRENT(integer(Unit.MILLIAMPERE)),
		BATTERY_POWER(watt()),
		BATTERY_CHARGE_VOLTAGE_LIMIT(integer(Unit.MILLIVOLT)),
		BATTERY_DISCHARGE_VOLTAGE_LIMIT(integer(Unit.MILLIVOLT)),
		LOAD_ACTIVE_POWER(watt()),
		EPS_ACTIVE_POWER(watt()),
		EPS_FREQUENCY(integer(Unit.MILLIHERTZ)),
		GENERATOR_ACTIVE_POWER(watt()),
		GENERATOR_FREQUENCY(integer(Unit.MILLIHERTZ)),
		GRID_IMPORT_ENERGY(energy()),
		GRID_EXPORT_ENERGY(energy()),
		BATTERY_CHARGE_ENERGY(energy()),
		BATTERY_DISCHARGE_ENERGY(energy()),
		BATTERY_TEMPURATURE(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS).persistencePriority(PersistencePriority.MEDIUM)),
		BATTERY_CHARGE_VOLTAGE(integer(Unit.MILLIVOLT)),
		BATTERY_CHARGE_CURRENT(integer(Unit.MILLIAMPERE)),
		BATTERY_DISCHARGE_VOLTAGE(integer(Unit.MILLIVOLT)),
		BATTERY_DISCHARGE_CURRENT(integer(Unit.MILLIAMPERE)),
		BATTERY_CAPACITY(integer(Unit.AMPERE_HOURS));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	private static Doc integer() {
		return integer(Unit.NONE);
	}

	private static Doc watt() {
		return integer(Unit.WATT);
	}

	private static Doc integer(Unit unit) {
		return Doc.of(OpenemsType.INTEGER).unit(unit).persistencePriority(PersistencePriority.HIGH);
	}

	private static Doc energy() {
		return Doc.of(OpenemsType.LONG).unit(Unit.CUMULATED_WATT_HOURS).persistencePriority(PersistencePriority.HIGH);
	}

	public default IntegerReadChannel getOperatingStateChannel() {
		return this.channel(ChannelId.OPERATING_STATE);
	}

	public default Value<Integer> getOperatingState() {
		return this.getOperatingStateChannel().value();
	}

	public default void _setOperatingState(Integer value) {
		this.getOperatingStateChannel().setNextValue(value);
	}

	public default IntegerReadChannel getRunModeChannel() {
		return this.channel(ChannelId.RUN_MODE);
	}

	public default Value<Integer> getRunMode() {
		return this.getRunModeChannel().value();
	}

	public default void _setRunMode(Integer value) {
		this.getRunModeChannel().setNextValue(value);
	}

	public default IntegerReadChannel getGridActivePowerChannel() {
		return this.channel(ChannelId.GRID_ACTIVE_POWER);
	}

	public default Value<Integer> getGridActivePower() {
		return this.getGridActivePowerChannel().value();
	}

	public default void _setGridActivePower(Integer value) {
		this.getGridActivePowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getGridActivePowerL1Channel() {
		return this.channel(ChannelId.GRID_ACTIVE_POWER_L1);
	}

	public default Value<Integer> getGridActivePowerL1() {
		return this.getGridActivePowerL1Channel().value();
	}

	public default void _setGridActivePowerL1(Integer value) {
		this.getGridActivePowerL1Channel().setNextValue(value);
	}

	public default IntegerReadChannel getGridActivePowerL2Channel() {
		return this.channel(ChannelId.GRID_ACTIVE_POWER_L2);
	}

	public default Value<Integer> getGridActivePowerL2() {
		return this.getGridActivePowerL2Channel().value();
	}

	public default void _setGridActivePowerL2(Integer value) {
		this.getGridActivePowerL2Channel().setNextValue(value);
	}

	public default IntegerReadChannel getGridActivePowerL3Channel() {
		return this.channel(ChannelId.GRID_ACTIVE_POWER_L3);
	}

	public default Value<Integer> getGridActivePowerL3() {
		return this.getGridActivePowerL3Channel().value();
	}

	public default void _setGridActivePowerL3(Integer value) {
		this.getGridActivePowerL3Channel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryStateChannel() {
		return this.channel(ChannelId.BATTERY_STATE);
	}

	public default Value<Integer> getBatteryState() {
		return this.getBatteryStateChannel().value();
	}

	public default void _setBatteryState(Integer value) {
		this.getBatteryStateChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatterySocChannel() {
		return this.channel(ChannelId.BATTERY_SOC);
	}

	public default Value<Integer> getBatterySoc() {
		return this.getBatterySocChannel().value();
	}

	public default void _setBatterySoc(Integer value) {
		this.getBatterySocChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryVoltageChannel() {
		return this.channel(ChannelId.BATTERY_VOLTAGE);
	}

	public default Value<Integer> getBatteryVoltage() {
		return this.getBatteryVoltageChannel().value();
	}

	public default void _setBatteryVoltage(Integer value) {
		this.getBatteryVoltageChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryCurrentChannel() {
		return this.channel(ChannelId.BATTERY_CURRENT);
	}

	public default Value<Integer> getBatteryCurrent() {
		return this.getBatteryCurrentChannel().value();
	}

	public default void _setBatteryCurrent(Integer value) {
		this.getBatteryCurrentChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryPowerChannel() {
		return this.channel(ChannelId.BATTERY_POWER);
	}

	public default Value<Integer> getBatteryPower() {
		return this.getBatteryPowerChannel().value();
	}

	public default void _setBatteryPower(Integer value) {
		this.getBatteryPowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryChargeVoltageLimitChannel() {
		return this.channel(ChannelId.BATTERY_CHARGE_VOLTAGE_LIMIT);
	}

	public default Value<Integer> getBatteryChargeVoltageLimit() {
		return this.getBatteryChargeVoltageLimitChannel().value();
	}

	public default void _setBatteryChargeVoltageLimit(Integer value) {
		this.getBatteryChargeVoltageLimitChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryDischargeVoltageLimitChannel() {
		return this.channel(ChannelId.BATTERY_DISCHARGE_VOLTAGE_LIMIT);
	}

	public default Value<Integer> getBatteryDischargeVoltageLimit() {
		return this.getBatteryDischargeVoltageLimitChannel().value();
	}

	public default void _setBatteryDischargeVoltageLimit(Integer value) {
		this.getBatteryDischargeVoltageLimitChannel().setNextValue(value);
	}

	public default IntegerReadChannel getLoadActivePowerChannel() {
		return this.channel(ChannelId.LOAD_ACTIVE_POWER);
	}

	public default Value<Integer> getLoadActivePower() {
		return this.getLoadActivePowerChannel().value();
	}

	public default void _setLoadActivePower(Integer value) {
		this.getLoadActivePowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getEpsActivePowerChannel() {
		return this.channel(ChannelId.EPS_ACTIVE_POWER);
	}

	public default Value<Integer> getEpsActivePower() {
		return this.getEpsActivePowerChannel().value();
	}

	public default void _setEpsActivePower(Integer value) {
		this.getEpsActivePowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getEpsFrequencyChannel() {
		return this.channel(ChannelId.EPS_FREQUENCY);
	}

	public default Value<Integer> getEpsFrequency() {
		return this.getEpsFrequencyChannel().value();
	}

	public default void _setEpsFrequency(Integer value) {
		this.getEpsFrequencyChannel().setNextValue(value);
	}

	public default IntegerReadChannel getGeneratorActivePowerChannel() {
		return this.channel(ChannelId.GENERATOR_ACTIVE_POWER);
	}

	public default Value<Integer> getGeneratorActivePower() {
		return this.getGeneratorActivePowerChannel().value();
	}

	public default void _setGeneratorActivePower(Integer value) {
		this.getGeneratorActivePowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getGeneratorFrequencyChannel() {
		return this.channel(ChannelId.GENERATOR_FREQUENCY);
	}

	public default Value<Integer> getGeneratorFrequency() {
		return this.getGeneratorFrequencyChannel().value();
	}

	public default void _setGeneratorFrequency(Integer value) {
		this.getGeneratorFrequencyChannel().setNextValue(value);
	}

	public default LongReadChannel getGridImportEnergyChannel() {
		return this.channel(ChannelId.GRID_IMPORT_ENERGY);
	}

	public default Value<Long> getGridImportEnergy() {
		return this.getGridImportEnergyChannel().value();
	}

	public default void _setGridImportEnergy(Long value) {
		this.getGridImportEnergyChannel().setNextValue(value);
	}

	public default LongReadChannel getGridExportEnergyChannel() {
		return this.channel(ChannelId.GRID_EXPORT_ENERGY);
	}

	public default Value<Long> getGridExportEnergy() {
		return this.getGridExportEnergyChannel().value();
	}

	public default void _setGridExportEnergy(Long value) {
		this.getGridExportEnergyChannel().setNextValue(value);
	}

	public default LongReadChannel getBatteryChargeEnergyChannel() {
		return this.channel(ChannelId.BATTERY_CHARGE_ENERGY);
	}

	public default Value<Long> getBatteryChargeEnergy() {
		return this.getBatteryChargeEnergyChannel().value();
	}

	public default void _setBatteryChargeEnergy(Long value) {
		this.getBatteryChargeEnergyChannel().setNextValue(value);
	}

	public default LongReadChannel getBatteryDischargeEnergyChannel() {
		return this.channel(ChannelId.BATTERY_DISCHARGE_ENERGY);
	}

	public default Value<Long> getBatteryDischargeEnergy() {
		return this.getBatteryDischargeEnergyChannel().value();
	}

	public default void _setBatteryDischargeEnergy(Long value) {
		this.getBatteryDischargeEnergyChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryTempuratureChannel() {
		return this.channel(ChannelId.BATTERY_TEMPURATURE);
	}

	public default Value<Integer> getBatteryTempurature() {
		return this.getGeneratorFrequencyChannel().value();
	}

	public default void _setBatteryTempurature(Integer value) {
		this.getGeneratorFrequencyChannel().setNextValue(value);
	}

}
