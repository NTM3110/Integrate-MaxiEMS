package io.openems.edge.common.claim;

import io.openems.edge.common.channel.Doc;

public interface Monitoring {
	public static final String SINGLETON_SERVICE_PID = "Monitoring.maxicom";
	public static final String SINGLETON_COMPONENT_ID = "_modem.maxicom";

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
