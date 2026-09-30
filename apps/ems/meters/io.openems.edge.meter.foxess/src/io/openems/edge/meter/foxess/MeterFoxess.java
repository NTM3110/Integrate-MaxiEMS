package io.openems.edge.meter.foxess;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface MeterFoxess extends ElectricityMeter, OpenemsComponent{

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DC1_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)),
		DC2_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)),
		ACTIVE_POWER_TOTAL(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT)),
		DC_TOTAL(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT)),
		ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT_HOURS)), //
		ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT_HOURS)),
		S_N(Doc.of(OpenemsType.STRING).text("Serial Number."))//
		;

		private final Doc doc;

		ChannelId(final Doc doc) {
			this.doc = doc;
		}

		/**
		 * Accessor method for the {@link Doc} instance of this channel.
		 *
		 * @return the Doc instance
		 * @see ChannelId#doc()
		 */
		@Override
		public Doc doc() {
			return this.doc;
		}
	}









}
