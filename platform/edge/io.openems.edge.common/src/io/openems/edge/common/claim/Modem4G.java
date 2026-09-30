package io.openems.edge.common.claim;

import io.openems.edge.common.channel.Doc;

public interface Modem4G {
	public static final String SINGLETON_SERVICE_PID = "Modem.4G";
	public static final String SINGLETON_COMPONENT_ID = "_modem.4g";

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

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
