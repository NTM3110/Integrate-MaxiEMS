package io.openems.edge.data.smartlogger;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface SmartLoggerData extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ENERGY_YESTERDAY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)), //
		MAX_ENERGY_TODAY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)), //
		DEBUG_LAST_DAILY_ENERGY(Doc.of(OpenemsType.FLOAT)
				.persistencePriority(PersistencePriority.HIGH)),
		CAPACITY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT)
				.persistencePriority(PersistencePriority.HIGH)); //

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
