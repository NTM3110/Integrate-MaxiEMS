package io.openems.edge.common.claim;

import io.openems.edge.common.channel.Doc;

public interface ModemWiFi {
	public static final String SINGLETON_SERVICE_PID = "Modem.WiFi";
	public static final String SINGLETON_COMPONENT_ID = "_modem.wifi";

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
