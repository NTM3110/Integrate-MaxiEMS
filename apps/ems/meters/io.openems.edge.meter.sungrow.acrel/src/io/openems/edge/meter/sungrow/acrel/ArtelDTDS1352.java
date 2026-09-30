package io.openems.edge.meter.sungrow.acrel;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.meter.api.ElectricityMeter;

public interface ArtelDTDS1352 extends ElectricityMeter {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		C_T(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		P_T(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		RAW_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE)), //
		RAW_CURRENT_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE)), //
		RAW_CURRENT_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE)), //
		RAW_CURRENT_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE)), //
				
		RAW_VOLTAGE_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT)), //
		RAW_VOLTAGE_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT)), //
		RAW_VOLTAGE_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT)),
		RAW_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		RAW_ACTIVE_POWER_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		RAW_ACTIVE_POWER_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		RAW_ACTIVE_POWER_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		
		RAW_APPARENT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE)), //
		RAW_APPARENT_POWER_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE)), //
		RAW_APPARENT_POWER_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE)), //
		RAW_APPARENT_POWER_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE)), //
				
		RAW_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE)), //
		RAW_REACTIVE_POWER_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE)), //
		RAW_REACTIVE_POWER_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE)), //
		RAW_REACTIVE_POWER_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE)), //
				
		POWER_FACTOR_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		POWER_FACTOR_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		POWER_FACTOR_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		POWER_FACTOR(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		RAW_FREQUENCY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.HERTZ)),//
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

}
