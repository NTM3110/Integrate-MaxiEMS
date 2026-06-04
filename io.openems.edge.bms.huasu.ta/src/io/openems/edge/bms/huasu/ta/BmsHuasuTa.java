package io.openems.edge.bms.huasu.ta;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.DoubleReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.channel.value.Value;

public interface BmsHuasuTa extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Battery Cell Voltage.
		 * 
		 * <ul>
		 * <li>Interface: BmsHuasuTa
		 * <li>Type: Integer
		 * <li>Unit: Millivolt (mV)
		 * </ul>
		 */
		BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) /// /SingleVoltage
				.unit(Unit.MILLIVOLT)), //

		/**
		 * Battery Cell Temperature.
		 * 
		 * <ul>
		 * <li>Interface: BmsHuasuTa
		 * <li>Type: Integer
		 * <li>Unit: DeciDegree Celsius (°dC)
		 * </ul>
		 */
		BATTERY_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS)), //

		/**
		 * Battery Single Internal Resistance.
		 * 
		 * <ul>
		 * <li>Interface: BmsHuasuTa
		 * <li>Type: Integer
		 * <li>Unit: MicroOhm (uOhm)
		 * </ul>
		 */
		BATTERY_INTERNAL_RESISTANCE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MICROOHM)
				.accessMode(AccessMode.READ_WRITE)),
		

		BATTERY_SOC(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.PERCENT)
				.accessMode(AccessMode.READ_WRITE)),

		BATTERY_SOH(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.PERCENT)
				.accessMode(AccessMode.READ_WRITE))
		; //

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
	 * Gets the Channel for {@link ChannelId#SINGLE_VOLTAGE}.
	 * 
	 * @return the Channel
	 */
	public default IntegerReadChannel getBatteryVoltageChannel() {
		return this.channel(ChannelId.BATTERY_VOLTAGE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_TEMPERATURE}.
	 * 
	 * @return the Channel
	 */
	public default IntegerReadChannel getBatteryTemperatureChannel() {
		return this.channel(ChannelId.BATTERY_TEMPERATURE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_INTERNAL_RESISTANCE}.
	 * 
	 * @return the Channel
	 */
	public default IntegerReadChannel getBatteryInternalResistanceChannel() {
		return this.channel(ChannelId.BATTERY_INTERNAL_RESISTANCE);
	}
	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_SOC}.
	 * 
	 * @return the Channel
	 */
	public default DoubleReadChannel getBatterySoCChannel() {
		return this.channel(ChannelId.BATTERY_SOC);
	}

	/**
	 * Gets the Single Voltage value.
	 * 
	 * @return the Single Voltage value (in mV)
	 */
	public default Value<Integer> getBatteryVoltage() {
		return this.getBatteryVoltageChannel().value();
	}

	/**
	 * Gets the battery temperature value for {@link ChannelId#BATTERY_TEMPERATURE}.
	 * 
	 * @return Battery temperature value (in °dC)
	 */
	public default Value<Integer> getBatteryTemperature() {
		return this.getBatteryTemperatureChannel().value();
	}

	/**
	 * Gets the Single Internal Resistance value.
	 * 
	 * @return the Single Internal Resistance value (in uOhm)
	 */
	public default Value<Integer> getBatteryInternalResistance() {
		return this.getBatteryInternalResistanceChannel().value();
	}

	/**
	 * Gets the Battery SoC value.
	 * 
	 * @return the SoC value (in percent)
	 */
	public default Value<Double> getBatterySoC(){
		return this.getBatterySoCChannel().value();
	}

	/**
	 * Sets the Battery SoC value.
	 * 
	 * @param soc the SoC value to set (in percent)
	 */
	public default void _setBatterySoC(double soc) {
		this.getBatterySoCChannel().setNextValue(soc);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_SOH}.
	 * 
	 * @return the Channel
	 */
	public default DoubleReadChannel getBatterySoHChannel() {
		return this.channel(ChannelId.BATTERY_SOH);
	}

	/**
	 * Gets the Battery SoH value.
	 * 
	 * @return the SoH value (in percent)
	 */
	public default Value<Double> getBatterySoH(){
		return this.getBatterySoHChannel().value();
	}
	/**
	 * Sets the Battery SoH value.
	 * 
	 * @param soh the SoH value to set (in percent)
	 */
	public default void _setBatterySoH(double soh) {
		this.getBatterySoHChannel().setNextValue(soh);
	}
}
