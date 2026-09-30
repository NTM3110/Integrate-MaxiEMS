package io.openems.edge.pvinverter.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Common monitoring nature for RTS PV inverters.
 *
 * <p>
 * The first {@link #PREDEFINED_DC_INPUT_COUNT} DC inputs are predefined. Vendor
 * drivers with more inputs can add further dynamic channels with
 * {@link RtsPvInverterChannelIdFactory}.
 */
public interface RtsPvInverter extends ElectricityMeter {

	/** Number of predefined DC input strings. */
	public static final int PREDEFINED_DC_INPUT_COUNT = 24;

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DC_INPUT_COUNT(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		/** Average voltage across all available DC input strings. */
		DC_VOLTAGE(dcVoltage()), //
		/** Sum of the currents of all available DC input strings. */
		DC_CURRENT(dcCurrent()), //
		DC_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //
		POWER_FACTOR(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.THOUSANDTH) //
				.persistencePriority(PersistencePriority.HIGH)), //

		DC1_VOLTAGE(dcVoltage()), //
		DC1_CURRENT(dcCurrent()), //
		DC2_VOLTAGE(dcVoltage()), //
		DC2_CURRENT(dcCurrent()), //
		DC3_VOLTAGE(dcVoltage()), //
		DC3_CURRENT(dcCurrent()), //
		DC4_VOLTAGE(dcVoltage()), //
		DC4_CURRENT(dcCurrent()), //
		DC5_VOLTAGE(dcVoltage()), //
		DC5_CURRENT(dcCurrent()), //
		DC6_VOLTAGE(dcVoltage()), //
		DC6_CURRENT(dcCurrent()), //
		DC7_VOLTAGE(dcVoltage()), //
		DC7_CURRENT(dcCurrent()), //
		DC8_VOLTAGE(dcVoltage()), //
		DC8_CURRENT(dcCurrent()), //
		DC9_VOLTAGE(dcVoltage()), //
		DC9_CURRENT(dcCurrent()), //
		DC10_VOLTAGE(dcVoltage()), //
		DC10_CURRENT(dcCurrent()), //
		DC11_VOLTAGE(dcVoltage()), //
		DC11_CURRENT(dcCurrent()), //
		DC12_VOLTAGE(dcVoltage()), //
		DC12_CURRENT(dcCurrent()), //
		DC13_VOLTAGE(dcVoltage()), //
		DC13_CURRENT(dcCurrent()), //
		DC14_VOLTAGE(dcVoltage()), //
		DC14_CURRENT(dcCurrent()), //
		DC15_VOLTAGE(dcVoltage()), //
		DC15_CURRENT(dcCurrent()), //
		DC16_VOLTAGE(dcVoltage()), //
		DC16_CURRENT(dcCurrent()), //
		DC17_VOLTAGE(dcVoltage()), //
		DC17_CURRENT(dcCurrent()), //
		DC18_VOLTAGE(dcVoltage()), //
		DC18_CURRENT(dcCurrent()), //
		DC19_VOLTAGE(dcVoltage()), //
		DC19_CURRENT(dcCurrent()), //
		DC20_VOLTAGE(dcVoltage()), //
		DC20_CURRENT(dcCurrent()),
		DC21_VOLTAGE(dcVoltage()), //
		DC21_CURRENT(dcCurrent()),
		DC22_VOLTAGE(dcVoltage()), //
		DC22_CURRENT(dcCurrent()),
		DC23_VOLTAGE(dcVoltage()), //
		DC23_CURRENT(dcCurrent()),
		DC24_VOLTAGE(dcVoltage()), //
		DC24_CURRENT(dcCurrent());

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

		private static Doc dcVoltage() {
			return dcInput(Unit.MILLIVOLT);
		}

		private static Doc dcCurrent() {
			return dcInput(Unit.MILLIAMPERE);
		}

		private static Doc dcInput(Unit unit) {
			return Doc.of(OpenemsType.INTEGER) //
					.unit(unit) //
					.persistencePriority(PersistencePriority.HIGH);
		}
	}

	public default IntegerReadChannel getDcInputCountChannel() {
		return this.channel(ChannelId.DC_INPUT_COUNT);
	}

	public default Value<Integer> getDcInputCount() {
		return this.getDcInputCountChannel().value();
	}

	public default void _setDcInputCount(Integer value) {
		this.getDcInputCountChannel().setNextValue(value);
	}

	public default IntegerReadChannel getDcVoltageChannel() {
		return this.channel(ChannelId.DC_VOLTAGE);
	}

	/**
	 * Gets the average voltage across all available DC input strings in [mV].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getDcVoltage() {
		return this.getDcVoltageChannel().value();
	}

	public default void _setDcVoltage(Integer value) {
		this.getDcVoltageChannel().setNextValue(value);
	}

	public default IntegerReadChannel getDcCurrentChannel() {
		return this.channel(ChannelId.DC_CURRENT);
	}

	/**
	 * Gets the sum of the currents of all available DC input strings in [mA].
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getDcCurrent() {
		return this.getDcCurrentChannel().value();
	}

	public default void _setDcCurrent(Integer value) {
		this.getDcCurrentChannel().setNextValue(value);
	}

	public default IntegerReadChannel getDcPowerChannel() {
		return this.channel(ChannelId.DC_POWER);
	}

	public default Value<Integer> getDcPower() {
		return this.getDcPowerChannel().value();
	}

	public default void _setDcPower(Integer value) {
		this.getDcPowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getPowerFactorChannel() {
		return this.channel(ChannelId.POWER_FACTOR);
	}

	public default Value<Integer> getPowerFactor() {
		return this.getPowerFactorChannel().value();
	}

	public default void _setPowerFactor(Integer value) {
		this.getPowerFactorChannel().setNextValue(value);
	}

	/**
	 * Gets the voltage Channel for a DC input string.
	 *
	 * @param inputIndex one-based DC input index
	 * @return the Channel
	 */
	public default IntegerReadChannel getDcVoltageChannel(int inputIndex) {
		return this.channel(RtsPvInverterChannelIdFactory.dcVoltage(inputIndex));
	}

	/**
	 * Gets the DC input string voltage in [mV].
	 *
	 * @param inputIndex one-based DC input index
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getDcVoltage(int inputIndex) {
		return this.getDcVoltageChannel(inputIndex).value();
	}

	/**
	 * Sets the next DC input string voltage value in [mV].
	 *
	 * @param inputIndex one-based DC input index
	 * @param value      the next value
	 */
	public default void _setDcVoltage(int inputIndex, Integer value) {
		this.getDcVoltageChannel(inputIndex).setNextValue(value);
	}

	/**
	 * Gets the current Channel for a DC input string.
	 *
	 * @param inputIndex one-based DC input index
	 * @return the Channel
	 */
	public default IntegerReadChannel getDcCurrentChannel(int inputIndex) {
		return this.channel(RtsPvInverterChannelIdFactory.dcCurrent(inputIndex));
	}

	/**
	 * Gets the DC input string current in [mA].
	 *
	 * @param inputIndex one-based DC input index
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getDcCurrent(int inputIndex) {
		return this.getDcCurrentChannel(inputIndex).value();
	}

	/**
	 * Sets the next DC input string current value in [mA].
	 *
	 * @param inputIndex one-based DC input index
	 * @param value      the next value
	 */
	public default void _setDcCurrent(int inputIndex, Integer value) {
		this.getDcCurrentChannel(inputIndex).setNextValue(value);
	}

	public default LongReadChannel getActiveProductionEnergyTodayChannel() {
		return this.channel(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY);
	}

	public default Value<Long> getActiveProductionEnergyToday() {
		return this.getActiveProductionEnergyTodayChannel().value();
	}

	public default void _setActiveProductionEnergyToday(Long value) {
		this.getActiveProductionEnergyTodayChannel().setNextValue(value);
	}
}
