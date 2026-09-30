package io.openems.edge.meter.sungrow;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface MeterSungrow extends ElectricityMeter {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		LOAD_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)), //
		DAILY_EXPORT_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
		DAILY_IMPORT_ENERGY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
		DAILY_DIRECT_ENERGY_CONSUMPTION(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS)), //
		TOTAL_DIRECT_ENERGY_CONSUMPTION(Doc.of(OpenemsType.LONG) //
				.unit(Unit.WATT_HOURS));

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
