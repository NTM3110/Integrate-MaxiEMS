package io.openems.edge.controller.pvinverter.powerlimit;

import io.openems.common.channel.AccessMode;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerPvInverterFixPowerLimit extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DEVICE(Doc.of(OpenemsType.STRING) //
				.accessMode(AccessMode.READ_WRITE)), //
		LIMIT(Doc.of(OpenemsType.STRING) //
				.accessMode(AccessMode.READ_WRITE));

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
