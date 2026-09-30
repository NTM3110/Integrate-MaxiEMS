package io.openems.edge.logger.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.meter.api.ElectricityMeter;

public interface SmartLogger extends ElectricityMeter {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.LONG).unit(Unit.WATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),

		ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH));

		private final Doc doc;

		ChannelId(final Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	default Channel<Long> getEnergyProductionTotalChannel() {
		return this.channel(ChannelId.ENERGY_PRODUCTION_TOTAL);
	}

	default io.openems.edge.common.channel.Channel<Float> getEnergyProductionTodayChannel() {
		return this.channel(ChannelId.ENERGY_PRODUCTION_TODAY);
	}
}
