package io.openems.edge.common.claim;

import io.openems.edge.common.channel.Doc;

public interface Network {
	public static final String SINGLETON_SERVICE_PID = "network.manager";
	public static final String SINGLETON_COMPONENT_ID = "_network";

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
