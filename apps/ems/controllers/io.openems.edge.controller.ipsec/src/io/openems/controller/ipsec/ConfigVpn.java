package io.openems.controller.ipsec;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ConfigVpn extends Controller, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		PHASE1_STATUS(Doc.of(OpenemsType.STRING) //
				.text("IKE SA Status")), //
		PHASE2_STATUS(Doc.of(OpenemsType.STRING) //
				.text("Child SA Status")), //
		VPN_ERROR_DETAILS(Doc.of(OpenemsType.STRING) //
				.text("VPN Connection Error Details")),
		PING_STATUS(Doc.of(OpenemsType.BOOLEAN)
				.text("Status VPN"))
		;

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
