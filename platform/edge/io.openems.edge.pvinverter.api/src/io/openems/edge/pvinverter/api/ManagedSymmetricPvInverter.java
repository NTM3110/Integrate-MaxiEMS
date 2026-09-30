package io.openems.edge.pvinverter.api;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Represents a 3-Phase, symmetric PV-Inverter.
 */
public interface ManagedSymmetricPvInverter extends ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Holds the maximum possible apparent power. This value is defined by the
		 * inverter limitations.
		 *
		 * <ul>
		 * <li>Interface: SymmetricPvInverter
		 * <li>Type: Integer
		 * <li>Unit: VA
		 * <li>Range: zero or positive value
		 * </ul>
		 */
		MAX_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.WATT)//
				.persistencePriority(PersistencePriority.MEDIUM)), //
		/**
		 * Holds the maximum possible apparent power. This value is defined by the
		 * inverter limitations.
		 *
		 * <ul>
		 * <li>Interface: SymmetricPvInverter
		 * <li>Type: Integer
		 * <li>Unit: VA
		 * <li>Range: zero or positive value
		 * </ul>
		 */
		MAX_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.VOLT_AMPERE_REACTIVE)//
				.persistencePriority(PersistencePriority.MEDIUM)), //
		/**
		 * Holds the maximum possible apparent power. This value is defined by the
		 * inverter limitations.
		 *
		 * <ul>
		 * <li>Interface: SymmetricPvInverter
		 * <li>Type: Integer
		 * <li>Unit: VA
		 * <li>Range: zero or positive value
		 * </ul>
		 */
		MAX_APPARENT_POWER(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.VOLT_AMPERE)//
				.persistencePriority(PersistencePriority.MEDIUM)), //
		/**
		 * Read/Set Active Power Limit.
		 *
		 * <ul>
		 * <li>Interface: PV-Inverter Symmetric
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		ACTIVE_POWER_LIMIT(new IntegerDoc()//
				.unit(Unit.WATT)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		REMOTE_CONTROL(new IntegerDoc()//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		REMOTE_CONTROL_P(new IntegerDoc()//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		REMOTE_CONTROL_Q(new IntegerDoc()//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		REACTIVE_POWER_LIMIT(new IntegerDoc()//
				.unit(Unit.VOLT_AMPERE_REACTIVE)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		/**
		 * Read/Set Active Power Limit in Percentage.
		 *
		 * <ul>
		 * <li>Interface: PV-Inverter Symmetric
		 * <li>Type: Integer
		 * <li>Unit: %
		 * <li>Range: 0-100
		 * </ul>
		 */
		ACTIVE_POWER_LIMIT_PERCENT(new IntegerDoc()//
				.unit(Unit.PERCENT)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		/**
		 * Read/Set Reactive Power Limit in Percentage.
		 *
		 * <ul>
		 * <li>Interface: PV-Inverter Symmetric
		 * <li>Type: Integer
		 * <li>Unit: %
		 * <li>Range: 0-100
		 * </ul>
		 */
		REACTIVE_POWER_LIMIT_PERCENT(new IntegerDoc()//
				.unit(Unit.PERCENT)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		MAX_DISCHARGE_CURRENT(new IntegerDoc()//
				.unit(Unit.AMPERE)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		MAX_CHARGE_CURRENT(new IntegerDoc()//
				.unit(Unit.AMPERE)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		MAX_CHARGE_SOC(new IntegerDoc()//
				.unit(Unit.PERCENT)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),
		MAX_DISCHARGE_SOC(new IntegerDoc()//
				.unit(Unit.PERCENT)//
				.accessMode(AccessMode.READ_WRITE)//
				.persistencePriority(PersistencePriority.MEDIUM)//
				.onInit(channel -> {
					//
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				}));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the type of this Meter.
	 *
	 * @return the MeterType
	 */
	@Override
	default MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_ACTIVE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxActivePowerChannel() {
		return this.channel(ChannelId.MAX_ACTIVE_POWER);
	}

	/**
	 * Gets the Maximum Active Power in [WATT], range "&gt;= 0". See
	 * {@link ChannelId#MAX_ACTIVE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxActivePower() {
		return this.getMaxActivePowerChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#MAX_ACTIVE_POWER}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxActivePower(Integer value) {
		this.getMaxActivePowerChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#MAX_ACTIVE_POWER}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxActivePower(int value) {
		this.getMaxActivePowerChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_REACTIVE_POWER}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getMaxReactivePowerChannel() {
		return this.channel(ChannelId.MAX_REACTIVE_POWER);
	}

	/**
	 * Gets the Maximum Reactive Power in [VAR], range "&gt;= 0". See
	 * {@link ChannelId#MAX_REACTIVE_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxReactivePower() {
		return this.getMaxReactivePowerChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_REACTIVE_POWER} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxReactivePower(Integer value) {
		this.getMaxReactivePowerChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_REACTIVE_POWER} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxReactivePower(int value) {
		this.getMaxReactivePowerChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#MAX_APPARENT_POWER}.
	 *
	 * @return the Channel
	 */
	/**
	 * Legacy alias for {@link #setReactivePowerLimit(Integer)}.
	 *
	 * @param value the reactive power limit in [var]
	 * @throws OpenemsNamedException on error
	 * @deprecated use {@link #setReactivePowerLimit(Integer)}
	 */
	@Deprecated
	public default void setMaxReactivePower(Integer value) throws OpenemsNamedException {
		this.setReactivePowerLimit(value);
	}

	/**
	 * Legacy alias for {@link #setReactivePowerLimit(int)}.
	 *
	 * @param value the reactive power limit in [var]
	 * @throws OpenemsNamedException on error
	 * @deprecated use {@link #setReactivePowerLimit(int)}
	 */
	@Deprecated
	public default void setMaxReactivePower(int value) throws OpenemsNamedException {
		this.setReactivePowerLimit(value);
	}

	public default IntegerReadChannel getMaxApparentPowerChannel() {
		return this.channel(ChannelId.MAX_APPARENT_POWER);
	}

	/**
	 * Gets the Maximum Apparent Power in [VA], range "&gt;= 0". See
	 * {@link ChannelId#MAX_APPARENT_POWER}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxApparentPower() {
		return this.getMaxApparentPowerChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_APPARENT_POWER} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxApparentPower(Integer value) {
		this.getMaxApparentPowerChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_APPARENT_POWER} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxApparentPower(int value) {
		this.getMaxApparentPowerChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getActivePowerLimitChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_LIMIT);
	}

	/**
	 * Gets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getActivePowerLimit() {
		return this.getActivePowerLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setActivePowerLimit(Integer value) {
		this.getActivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setActivePowerLimit(int value) {
		this.getActivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setActivePowerLimit(Integer value) throws OpenemsNamedException {
		this.getActivePowerLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setActivePowerLimit(int value) throws OpenemsNamedException {
		this.getActivePowerLimitChannel().setNextWriteValue(value);
	}

	// ===============================================================
	// Reactive Power Limit Channel Methods (Absolute value in var)
	// ===============================================================

	/**
	 * Gets the Channel for {@link ChannelId#REACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getReactivePowerLimitChannel() {
		return this.channel(ChannelId.REACTIVE_POWER_LIMIT);
	}

	/**
	 * Gets the Reactive Power Limit in [var]. See
	 * {@link ChannelId#REACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getReactivePowerLimit() {
		return this.getReactivePowerLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReactivePowerLimit(Integer value) {
		this.getReactivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReactivePowerLimit(int value) {
		this.getReactivePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the Reactive Power Limit in [var]. See
	 * {@link ChannelId#REACTIVE_POWER_LIMIT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setReactivePowerLimit(Integer value) throws OpenemsNamedException {
		this.getReactivePowerLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Reactive Power Limit in [var]. See
	 * {@link ChannelId#REACTIVE_POWER_LIMIT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setReactivePowerLimit(int value) throws OpenemsNamedException {
		this.getReactivePowerLimitChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getMaxChargeCurrentLimitChannel() {
		return this.channel(ChannelId.MAX_CHARGE_CURRENT);
	}

	/**
	 * Gets the Max Charge Current Limit in [A]. See
	 * {@link ChannelId#MAX_CHARGE_CURRENT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxChargeCurrentLimit() {
		return this.getMaxChargeCurrentLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_CHARGE_CURRENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxChargeCurrentLimit(Integer value) {
		this.getMaxChargeCurrentLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_CHARGE_CURRENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxChargeCurrentLimit(int value) {
		this.getMaxChargeCurrentLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the Max Charge Current Limit in [A]. See
	 * {@link ChannelId#MAX_CHARGE_CURRENT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxChargeCurrentLimit(Integer value) throws OpenemsNamedException {
		this.getMaxChargeCurrentLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Max Charge Current Limit in [A]. See
	 * {@link ChannelId#MAX_CHARGE_CURRENT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxChargeCurrentLimit(int value) throws OpenemsNamedException {
		this.getMaxChargeCurrentLimitChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getMaxDischargeCurrentLimitChannel() {
		return this.channel(ChannelId.MAX_DISCHARGE_CURRENT);
	}

	/**
	 * Gets the Max Discharge Current Limit in [A]. See
	 * {@link ChannelId#MAX_DISCHARGE_CURRENT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxDischargeCurrentLimit() {
		return this.getMaxDischargeCurrentLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_DISCHARGE_CURRENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxDischargeCurrentLimit(Integer value) {
		this.getMaxDischargeCurrentLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_DISCHARGE_CURRENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxDischargeCurrentLimit(int value) {
		this.getMaxDischargeCurrentLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the Max Discharge Current Limit in [A]. See
	 * {@link ChannelId#MAX_DISCHARGE_CURRENT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxDischargeCurrentLimit(Integer value) throws OpenemsNamedException {
		this.getMaxDischargeCurrentLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Max Discharge Current Limit in [A]. See
	 * {@link ChannelId#MAX_DISCHARGE_CURRENT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxDischargeCurrentLimit(int value) throws OpenemsNamedException {
		this.getMaxDischargeCurrentLimitChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getMaxChargeSocLimitChannel() {
		return this.channel(ChannelId.MAX_CHARGE_SOC);
	}

	/**
	 * Gets the Max Charge SOC Limit in [%]. See {@link ChannelId#MAX_CHARGE_SOC}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxChargeSocLimit() {
		return this.getMaxChargeSocLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_CHARGE_SOC} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxChargeSocLimit(Integer value) {
		this.getMaxChargeSocLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_CHARGE_SOC} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxChargeSocLimit(int value) {
		this.getMaxChargeSocLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the Max Charge SOC Limit in [%]. See {@link ChannelId#MAX_CHARGE_SOC}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxChargeSocLimit(Integer value) throws OpenemsNamedException {
		this.getMaxChargeSocLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Max Charge SOC Limit in [%]. See {@link ChannelId#MAX_CHARGE_SOC}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxChargeSocLimit(int value) throws OpenemsNamedException {
		this.getMaxChargeSocLimitChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getMaxDischargeSocLimitChannel() {
		return this.channel(ChannelId.MAX_DISCHARGE_SOC);
	}

	/**
	 * Gets the Max Discharge SOC Limit in [%]. See
	 * {@link ChannelId#MAX_DISCHARGE_SOC}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getMaxDischargeSocLimit() {
		return this.getMaxDischargeSocLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_DISCHARGE_SOC} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxDischargeSocLimit(Integer value) {
		this.getMaxDischargeSocLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#MAX_DISCHARGE_SOC} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMaxDischargeSocLimit(int value) {
		this.getMaxDischargeSocLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the Max Discharge SOC Limit in [%]. See
	 * {@link ChannelId#MAX_DISCHARGE_SOC}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxDischargeSocLimit(Integer value) throws OpenemsNamedException {
		this.getMaxDischargeSocLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Max Discharge SOC Limit in [%]. See
	 * {@link ChannelId#MAX_DISCHARGE_SOC}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setMaxDischargeSocLimit(int value) throws OpenemsNamedException {
		this.getMaxDischargeSocLimitChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getModesChannel() {
		return this.channel(ChannelId.REMOTE_CONTROL);
	}

	/**
	 * Gets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getModes() {
		return this.getModesChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setModes(Integer value) {
		this.getModesChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setModes(int value) {
		this.getModesChannel().setNextValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setModes(Integer value) throws OpenemsNamedException {
		this.getModesChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setModes(int value) throws OpenemsNamedException {
		this.getModesChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getModesPChannel() {
		return this.channel(ChannelId.REMOTE_CONTROL_P);
	}

	/**
	 * Gets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getModesP() {
		return this.getModesPChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setModesP(Integer value) {
		this.getModesPChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setModesP(int value) {
		this.getModesPChannel().setNextValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setModesP(Integer value) throws OpenemsNamedException {
		this.getModesPChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setModesP(int value) throws OpenemsNamedException {
		this.getModesPChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getModesQChannel() {
		return this.channel(ChannelId.REMOTE_CONTROL_Q);
	}

	/**
	 * Gets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getModesQ() {
		return this.getModesQChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setModesQ(Integer value) {
		this.getModesQChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setModesQ(int value) {
		this.getModesQChannel().setNextValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setModesQ(Integer value) throws OpenemsNamedException {
		this.getModesQChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Active Power Limit in [W]. See {@link ChannelId#ACTIVE_POWER_LIMIT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setModesQ(int value) throws OpenemsNamedException {
		this.getModesQChannel().setNextWriteValue(value);
	}

	/**
	 * Used for Modbus/TCP Api Controller. Provides a Modbus table for the Channels
	 * of this Component.
	 *
	 * @param accessMode filters the Modbus-Records that should be shown
	 * @return the {@link ModbusSlaveNatureTable}
	 */

	// ===============================================================
	// Active Power Limit Percent Channel Methods
	// ===============================================================

	/**
	 * Gets the Channel for {@link ChannelId#ACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getActivePowerLimitPercentChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_LIMIT_PERCENT);
	}

	/**
	 * Gets the Active Power Limit in [%]. See
	 * {@link ChannelId#ACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getActivePowerLimitPercent() {
		return this.getActivePowerLimitPercentChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT_PERCENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setActivePowerLimitPercent(Integer value) {
		this.getActivePowerLimitPercentChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ACTIVE_POWER_LIMIT_PERCENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setActivePowerLimitPercent(int value) {
		this.getActivePowerLimitPercentChannel().setNextValue(value);
	}

	/**
	 * Sets the Active Power Limit in [%]. See
	 * {@link ChannelId#ACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @param value the Integer value (0-100)
	 * @throws OpenemsNamedException on error
	 */
	public default void setActivePowerLimitPercent(Integer value) throws OpenemsNamedException {
		this.getActivePowerLimitPercentChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Active Power Limit in [%]. See
	 * {@link ChannelId#ACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @param value the int value (0-100)
	 * @throws OpenemsNamedException on error
	 */
	public default void setActivePowerLimitPercent(int value) throws OpenemsNamedException {
		this.getActivePowerLimitPercentChannel().setNextWriteValue(value);
	}

	// ===============================================================
	// Reactive Power Limit Percent Channel Methods
	// ===============================================================

	/**
	 * Gets the Channel for {@link ChannelId#REACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getReactivePowerLimitPercentChannel() {
		return this.channel(ChannelId.REACTIVE_POWER_LIMIT_PERCENT);
	}

	/**
	 * Gets the Reactive Power Limit in [%]. See
	 * {@link ChannelId#REACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getReactivePowerLimitPercent() {
		return this.getReactivePowerLimitPercentChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REACTIVE_POWER_LIMIT_PERCENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReactivePowerLimitPercent(Integer value) {
		this.getReactivePowerLimitPercentChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#REACTIVE_POWER_LIMIT_PERCENT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReactivePowerLimitPercent(int value) {
		this.getReactivePowerLimitPercentChannel().setNextValue(value);
	}

	/**
	 * Sets the Reactive Power Limit in [%]. See
	 * {@link ChannelId#REACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @param value the Integer value (0-100)
	 * @throws OpenemsNamedException on error
	 */
	public default void setReactivePowerLimitPercent(Integer value) throws OpenemsNamedException {
		this.getReactivePowerLimitPercentChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Reactive Power Limit in [%]. See
	 * {@link ChannelId#REACTIVE_POWER_LIMIT_PERCENT}.
	 *
	 * @param value the int value (0-100)
	 * @throws OpenemsNamedException on error
	 */
	public default void setReactivePowerLimitPercent(int value) throws OpenemsNamedException {
		this.getReactivePowerLimitPercentChannel().setNextWriteValue(value);
	}

	public static ModbusSlaveNatureTable getModbusSlaveNatureTable(AccessMode accessMode) {
		return ModbusSlaveNatureTable.of(ManagedSymmetricPvInverter.class, accessMode, 100) //
				.build();
	}
}
