package io.openems.edge.bridge.iec104.api;

import io.openems.common.channel.Debounce;
import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;

/**
 * A Component that requires an IEC 104 connection.
 */
public interface Iec104Component extends OpenemsComponent, ComponentJsonApi {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		IEC104_COMMUNICATION_FAILED(Doc.of(Level.WARNING) //
				.debounce(10, Debounce.SAME_VALUES_IN_A_ROW_TO_CHANGE) //
				.text("IEC 104 Communication failed"));

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
	 * Gets the Channel for {@link ChannelId#IEC104_COMMUNICATION_FAILED}.
	 * 
	 * @return the Channel
	 */
	public default StateChannel getIec104CommunicationFailedChannel() {
		return this.channel(Iec104Component.ChannelId.IEC104_COMMUNICATION_FAILED);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#IEC104_COMMUNICATION_FAILED} Channel.
	 * 
	 * @param value the next value
	 */
	public default void _setIec104CommunicationFailed(boolean value) {
		this.getIec104CommunicationFailedChannel().setNextValue(value);
	}

	/**
	 * Gets the Common Address associated with this component.
	 * 
	 * @return the Common Address
	 */
	public int getCommonAddress();

	/**
	 * Gets the {@link Iec104Protocol} of this Component.
	 * 
	 * @return the {@link Iec104Protocol}
	 */
	public Iec104Protocol getIec104Protocol();

}
