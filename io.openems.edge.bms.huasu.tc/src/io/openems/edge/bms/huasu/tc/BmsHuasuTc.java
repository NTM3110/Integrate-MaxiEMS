package io.openems.edge.bms.huasu.tc;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.DoubleReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.channel.value.Value;

public interface BmsHuasuTc extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Battery Cell Voltage.
		 * 
		 * <ul>
		 * <li>Interface: BmsHuasuTc
		 * <li>Type: Integer
		 * <li>Unit: DeziAmpere (DA)
		 * </ul>
		 */
		STRING_CURRENT(Doc.of(OpenemsType.INTEGER) /// /SingleVoltage
				.unit(Unit.DEZIAMPERE)), //

		/**
		 * String Ambient Temperature.
		 * 
		 * <ul>
		 * <li>Interface: BmsHuasuTc
		 * <li>Type: Integer
		 * <li>Unit: DeciDegree Celsius (°dC)
		 * </ul>
		 */
		AMBIENT_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS)
				.accessMode(AccessMode.READ_ONLY)), //

		
		STRING_SOC(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.PERCENT)
				.accessMode(AccessMode.READ_ONLY)),
		
		STRING_SOH(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.PERCENT)
				.accessMode(AccessMode.READ_ONLY)),

		STRING_VOLTAGE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.VOLT)
				.accessMode(AccessMode.READ_ONLY)),

		MAX_VOLTAGE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.VOLT)
				.accessMode(AccessMode.READ_ONLY)),
		
		MAX_VOLTAGE_CELL_INDEX(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)),

		MIN_VOLTAGE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.VOLT)
				.accessMode(AccessMode.READ_ONLY)),
		
		MIN_VOLTAGE_CELL_INDEX(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)),
		
		MIN_TEMPERATURE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.DEGREE_CELSIUS)
				.accessMode(AccessMode.READ_ONLY)),
		
		MIN_TEMPERATURE_CELL_INDEX(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)),
		
		MAX_TEMPERATURE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.DEGREE_CELSIUS)
				.accessMode(AccessMode.READ_ONLY)),

		MAX_TEMPERATURE_CELL_INDEX(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)),
		
		MAX_RESISTANCE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MICROOHM)
				.accessMode(AccessMode.READ_ONLY)),

		MAX_RESISTANCE_CELL_INDEX(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)),
		
		MIN_RESISTANCE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MICROOHM)
				.accessMode(AccessMode.READ_ONLY)),
		
		MIN_RESISTANCE_CELL_INDEX(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)),
		
		AVG_VOLTAGE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.VOLT)
				.accessMode(AccessMode.READ_ONLY)),
		
		AVG_TEMPERATURE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.DEGREE_CELSIUS)
				.accessMode(AccessMode.READ_ONLY)),
		
		AVG_RESISTANCE(Doc.of(OpenemsType.DOUBLE) //
				.unit(Unit.MICROOHM)
				.accessMode(AccessMode.READ_ONLY))
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
	public default IntegerReadChannel getStringCurrentChannel() {
		return this.channel(ChannelId.STRING_CURRENT);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BATTERY_TEMPERATURE}.
	 * 
	 * @return the Channel
	 */
	public default IntegerReadChannel getAmbientTemperatureChannel() {
		return this.channel(ChannelId.AMBIENT_TEMPERATURE);
	}

	/**
	 * Gets the Channel for {@link ChannelId#STRING_SOC}.
	 * 
	 * @return the Channel
	 */	public default DoubleReadChannel getStringSocChannel() {
		return this.channel(ChannelId.STRING_SOC);
	}

	/**
	 * Gets the Channel for {@link ChannelId#STRING_SOH}.
	 * 
	 * @return the Channel
	 */	public default DoubleReadChannel getStringSoHChannel() {
		return this.channel(ChannelId.STRING_SOH);
	}

	/**
	 * Gets the string current value for {@link ChannelId#STRING_CURRENT}.
	 * 
	 * @return String current value (in DA)
	 */
	public default Value<Integer> getStringCurrent(){
		return this.getStringCurrentChannel().value();
	}

	/**
	 * Gets the ambient temperature value for {@link ChannelId#AMBIENT_TEMPERATURE}.
	 * 
	 * @return Ambient temperature value (in °dC)
	 */
	public default Value<Integer> getAmbientTemperature(){
		return this.getAmbientTemperatureChannel().value();
	}

	/**
	 * Gets the string SoC value for {@link ChannelId#STRING_SOC}.
	 * 
	 * @return String SoC value (in %)
	 */
	public default Value<Double> getStringSoC(){
		return this.getStringSocChannel().value();
	}

	/**
	 * Gets the string SoH value for {@link ChannelId#STRING_SOH}.
	 * 
	 * @return String SoH value (in %)
	 */
	public default Value<Double> getStringSoH(){
		return this.getStringSoHChannel().value();
	}

	/**
	 * Sets the string SoC value for {@link ChannelId#STRING_SOC}.
	 * 
	 * @param soc String SoC value (in %)
	 */
	public default void _setStringSoC(double soc) {
		this.channel(ChannelId.STRING_SOC).setNextValue(soc);
	}
	
	/**
	 * Sets the string SoH value for {@link ChannelId#STRING_SOH}.
	 * 
	 * @param soh String SoH value (in %)
	 */
	public default void _setStringSoH(double soh) {
		this.channel(ChannelId.STRING_SOH).setNextValue(soh);
	}

	/**
	 * Gets the Channel for {@link ChannelId#STRING_VOLTAGE}.
	 * 
	 * @return the Channel
	 */
	public default DoubleReadChannel getStringVoltageChannel() {
		return this.channel(ChannelId.STRING_VOLTAGE);
	}

	/**
	 * Gets the string voltage value for {@link ChannelId#STRING_VOLTAGE}.
	 * 
	 * @return String voltage value (in V)
	 */
	public default Value<Double> getStringVoltage() {
		return this.getStringVoltageChannel().value();
	}

	public default void _setStringVoltage(double voltage) {
		this.channel(ChannelId.STRING_VOLTAGE).setNextValue(voltage);
	}

	public default DoubleReadChannel getMaxVoltageChannel() {
		return this.channel(ChannelId.MAX_VOLTAGE);
	}

	public default Value<Double> getMaxVoltage() {
		return this.getMaxVoltageChannel().value();
	}

	public default void _setMaxVoltage(double voltage) {
		this.channel(ChannelId.MAX_VOLTAGE).setNextValue(voltage);
	}

	public default IntegerReadChannel getMaxVoltageCellIndexChannel() {
		return this.channel(ChannelId.MAX_VOLTAGE_CELL_INDEX);
	}
	
	public default Value<Integer> getMaxVoltageCellIndex() {
		return this.getMaxVoltageCellIndexChannel().value();
	}

	public default void _setMaxVoltageCellIndex(int index) {
		this.channel(ChannelId.MAX_VOLTAGE_CELL_INDEX).setNextValue(index);
	}

	public default DoubleReadChannel getMinVoltageChannel() {
		return this.channel(ChannelId.MIN_VOLTAGE);
	}

	public default Value<Double> getMinVoltage() {
		return this.getMinVoltageChannel().value();
	}

	public default void _setMinVoltage(double voltage) {
		this.channel(ChannelId.MIN_VOLTAGE).setNextValue(voltage);
	}

	public default IntegerReadChannel getMinVoltageCellIndexChannel() {
		return this.channel(ChannelId.MIN_VOLTAGE_CELL_INDEX);
	}

	public default Value<Integer> getMinVoltageCellIndex() {
		return this.getMinVoltageCellIndexChannel().value();
	}

	public default void _setMinVoltageCellIndex(int index) {
		this.channel(ChannelId.MIN_VOLTAGE_CELL_INDEX).setNextValue(index);
	}

	public default DoubleReadChannel getMinTemperatureChannel() {
		return this.channel(ChannelId.MIN_TEMPERATURE);
	}

	public default Value<Double> getMinTemperature() {
		return this.getMinTemperatureChannel().value();
	}

	public default void _setMinTemperature(double temperature) {
		this.channel(ChannelId.MIN_TEMPERATURE).setNextValue(temperature);
	}

	public default IntegerReadChannel getMinTemperatureCellIndexChannel() {
		return this.channel(ChannelId.MIN_TEMPERATURE_CELL_INDEX);
	}

	public default Value<Integer> getMinTemperatureCellIndex() {
		return this.getMinTemperatureCellIndexChannel().value();
	}

	public default void _setMinTemperatureCellIndex(int index) {
		this.channel(ChannelId.MIN_TEMPERATURE_CELL_INDEX).setNextValue(index);
	}

	public default DoubleReadChannel getMaxTemperatureChannel() {
		return this.channel(ChannelId.MAX_TEMPERATURE);
	}

	public default Value<Double> getMaxTemperature() {
		return this.getMaxTemperatureChannel().value();
	}

	public default void _setMaxTemperature(double temperature) {
		this.channel(ChannelId.MAX_TEMPERATURE).setNextValue(temperature);
	}

	public default IntegerReadChannel getMaxTemperatureCellIndexChannel() {
		return this.channel(ChannelId.MAX_TEMPERATURE_CELL_INDEX);
	}

	public default Value<Integer> getMaxTemperatureCellIndex() {
		return this.getMaxTemperatureCellIndexChannel().value();
	}

	public default void _setMaxTemperatureCellIndex(int index) {
		this.channel(ChannelId.MAX_TEMPERATURE_CELL_INDEX).setNextValue(index);
	}

	public default DoubleReadChannel getAvgVoltageChannel() {
		return this.channel(ChannelId.AVG_VOLTAGE);
	}

	public default Value<Double> getAvgVoltage() {
		return this.getAvgVoltageChannel().value();
	}

	public default void _setAvgVoltage(double voltage) {
		this.channel(ChannelId.AVG_VOLTAGE).setNextValue(voltage);
	}

	public default DoubleReadChannel getAvgTemperatureChannel() {
		return this.channel(ChannelId.AVG_TEMPERATURE);
	}

	public default Value<Double> getAvgTemperature() {
		return this.getAvgTemperatureChannel().value();
	}

	public default void _setAvgTemperature(double temperature) {
		this.channel(ChannelId.AVG_TEMPERATURE).setNextValue(temperature);
	}

	public default IntegerReadChannel getMaxResistanceChannel() {
		return this.channel(ChannelId.MAX_RESISTANCE);
	}

	public default Value<Integer> getMaxResistance() {
		return this.getMaxResistanceChannel().value();
	}

	public default void _setMaxResistance(int resistance) {
		this.channel(ChannelId.MAX_RESISTANCE).setNextValue(resistance);
	}

	public default IntegerReadChannel getMaxResistanceCellIndexChannel() {
		return this.channel(ChannelId.MAX_RESISTANCE_CELL_INDEX);
	}

	public default Value<Integer> getMaxResistanceCellIndex() {
		return this.getMaxResistanceCellIndexChannel().value();
	}

	public default void _setMaxResistanceCellIndex(int index) {
		this.channel(ChannelId.MAX_RESISTANCE_CELL_INDEX).setNextValue(index);
	}

	public default IntegerReadChannel getMinResistanceChannel() {
		return this.channel(ChannelId.MIN_RESISTANCE);
	}

	public default Value<Integer> getMinResistance() {
		return this.getMinResistanceChannel().value();
	}

	public default void _setMinResistance(int resistance) {
		this.channel(ChannelId.MIN_RESISTANCE).setNextValue(resistance);
	}

	public default IntegerReadChannel getMinResistanceCellIndexChannel() {
		return this.channel(ChannelId.MIN_RESISTANCE_CELL_INDEX);
	}

	public default Value<Integer> getMinResistanceCellIndex() {
		return this.getMinResistanceCellIndexChannel().value();
	}

	public default void _setMinResistanceCellIndex(int index) {
		this.channel(ChannelId.MIN_RESISTANCE_CELL_INDEX).setNextValue(index);
	}

	public default DoubleReadChannel getAvgResistanceChannel() {
		return this.channel(ChannelId.AVG_RESISTANCE);
	}

	public default Value<Double> getAvgResistance() {
		return this.getAvgResistanceChannel().value();
	}

	public default void _setAvgResistance(double resistance) {
		this.channel(ChannelId.AVG_RESISTANCE).setNextValue(resistance);
	}


}

