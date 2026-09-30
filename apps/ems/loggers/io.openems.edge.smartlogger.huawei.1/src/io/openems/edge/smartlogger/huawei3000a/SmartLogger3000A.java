package io.openems.edge.smartlogger.huawei3000a;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface SmartLogger3000A extends ManagedSymmetricPvInverter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ACTIVE_POWER_TOTAL(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)),
		TOTAL_ENERGY(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT_HOURS)), //
		DAILY_ENERGY(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT_HOURS)), //
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_WRITE)), //
		P_LIMIT_TYPE(Doc.of(PLimitType.values()).accessMode(AccessMode.WRITE_ONLY)), //
		Q_LIMIT_TYPE(Doc.of(QLimitType.values()).accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT_PERC(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_WRITE).unit(Unit.PERCENT));

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
