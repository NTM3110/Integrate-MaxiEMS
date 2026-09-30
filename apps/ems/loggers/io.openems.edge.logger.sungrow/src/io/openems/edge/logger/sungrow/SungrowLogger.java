package io.openems.edge.logger.sungrow;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;

public interface SungrowLogger extends SmartLogger, ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DC1_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT).persistencePriority(PersistencePriority.HIGH)),
		DC2_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT).persistencePriority(PersistencePriority.HIGH)),
		DC3_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT).persistencePriority(PersistencePriority.HIGH)),
		DC4_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT).persistencePriority(PersistencePriority.HIGH)),
		DC1_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE).persistencePriority(PersistencePriority.HIGH)),
		DC2_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE).persistencePriority(PersistencePriority.HIGH)),
		DC3_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE).persistencePriority(PersistencePriority.HIGH)),
		DC4_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE).persistencePriority(PersistencePriority.HIGH)),
		DC_TOTAL(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT).persistencePriority(PersistencePriority.HIGH)),
		S_N(Doc.of(OpenemsType.STRING).text("Serial Number.").persistencePriority(PersistencePriority.HIGH)),
		DEVICE(Doc.of(OpenemsType.INTEGER).text("Device Type Code.").persistencePriority(PersistencePriority.HIGH));

		private final Doc doc;

		ChannelId(final Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
