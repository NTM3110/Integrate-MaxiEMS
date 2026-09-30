package io.openems.edge.meter.elster.a1700;

import io.openems.common.channel.Level;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/** OpenEMS nature for an Elster A1700 meter. */
public interface ElsterA1700Meter extends ElectricityMeter, OpenemsComponent {
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		COMMUNICATION_FAILED(Doc.of(Level.FAULT)),
		AUTHENTICATION_FAILED(Doc.of(Level.FAULT)),
		WRONG_METER(Doc.of(Level.FAULT)),
		PARTIAL_DECODE(Doc.of(Level.WARNING)),
		LAST_SUCCESSFUL_READ(Doc.of(OpenemsType.STRING)),
		LAST_ERROR(Doc.of(OpenemsType.STRING)),
		LAST_CURRENT_JSON(Doc.of(OpenemsType.STRING)),
		LAST_INSTANTANEOUS_JSON(Doc.of(OpenemsType.STRING)),
		LAST_HISTORICAL_JSON(Doc.of(OpenemsType.STRING)),
		LAST_PROFILE_JSON(Doc.of(OpenemsType.STRING));

		private final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
