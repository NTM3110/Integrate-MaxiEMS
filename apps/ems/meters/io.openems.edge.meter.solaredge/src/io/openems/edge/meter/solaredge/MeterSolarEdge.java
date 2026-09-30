package io.openems.edge.meter.solaredge;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface MeterSolarEdge extends ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		EXPORTED_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
		IMPORTED_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
		
		CURRENT_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		VOLTAGE_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		POWER_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		FREQUENCY_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		ACTIVE_POWER_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		APPARENT_POWER_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		REACTIVE_POWER_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		FACTOR_POWER_SF(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		EXPORTED_ENERGY_SF(Doc.of(OpenemsType.INTEGER) //
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
				.unit(Unit.VOLT)), //
				
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
				
		RAW_FACTOR_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		RAW_FACTOR_POWER_L1(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		RAW_FACTOR_POWER_L2(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		RAW_FACTOR_POWER_L3(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE)), //
		
		RAW_EXPORTED_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
		RAW_IMPORTED_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
				
		RAW_FREQUENCY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.HERTZ));
		;//
				

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
