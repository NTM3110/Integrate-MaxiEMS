package io.openems.edge.wika.api;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;

/** Alarm channels provided by a Wika AMS01 device. */
public interface WikaAms01Alarm extends WikaManagement {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		IN_ALARM_TEMP_FLAG(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH)),
		OUT_ALARM_TEMP_FLAG(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH)),
		MBA_ALARM_TEMP_FLAG(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH)),
		IN_ALARM_TEMP_VALUE(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		OUT_ALARM_TEMP_VALUE(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		MBA_ALARM_TEMP_VALUE(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		IN_ALARM_PD_FLAG(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH)),
		OUT_ALARM_PD_FLAG(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH)),
		MBA_ALARM_PD_FLAG(Doc.of(OpenemsType.INTEGER).persistencePriority(PersistencePriority.HIGH));

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
