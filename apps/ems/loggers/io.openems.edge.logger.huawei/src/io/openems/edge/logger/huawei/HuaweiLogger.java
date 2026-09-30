package io.openems.edge.logger.huawei;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;

public interface HuaweiLogger extends SmartLogger, ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		S_N(Doc.of(OpenemsType.STRING).text("Serial Number.")
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
}
