package io.openems.edge.config.manager;

import java.io.IOException;

import com.google.gson.JsonObject;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface ConfigManger extends OpenemsComponent {

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

	/**
	 * Exports all user-relevant OSGi configurations to JSON.
	 *
	 * @return the exported configurations
	 * @throws IOException on error
	 */
	public JsonObject exportAllConfigsToJson() throws IOException;

	/**
	 * Imports OSGi configurations from JSON and updates ConfigurationAdmin.
	 *
	 * @param data the configuration payload
	 * @throws IOException on error
	 */
	public void importConfigsFromJson(JsonObject data) throws IOException;
}
