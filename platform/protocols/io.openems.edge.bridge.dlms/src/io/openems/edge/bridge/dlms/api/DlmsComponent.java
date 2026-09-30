package io.openems.edge.bridge.dlms.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.Debounce;
import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * A OpenEMS Component that uses DLMS communication.
 *
 * <p>
 * Classes implementing this interface typically inherit
 * {@link AbstractOpenemsDlmsComponent}.
 */
@ProviderType
public interface DlmsComponent extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		DLMS_COMMUNICATION_FAILED(Doc.of(Level.WARNING) //
				.debounce(10, Debounce.SAME_VALUES_IN_A_ROW_TO_CHANGE) //
				.text("DLMS Communication failed")) //
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
	 * Gets the Channel for {@link ChannelId#DLMS_COMMUNICATION_FAILED}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getDlmsCommunicationFailedChannel() {
		return this.channel(DlmsComponent.ChannelId.DLMS_COMMUNICATION_FAILED);
	}

	/**
	 * Gets the DLMS Communication Failed State. See
	 * {@link ChannelId#DLMS_COMMUNICATION_FAILED}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default boolean getDlmsCommunicationFailed() {
		return this.getDlmsCommunicationFailedChannel().value().get();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#DLMS_COMMUNICATION_FAILED} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setDlmsCommunicationFailed(boolean value) {
		this.getDlmsCommunicationFailedChannel().setNextValue(value);
	}

	/**
	 * The DLMS Bridge marks defective Components, e.g. if there are communication
	 * failures. If a component is marked as defective, reads and writes are paused
	 * for an increasing waiting time. This method resets the waiting time, causing
	 * the DLMS Bridge to retry if a Component is not anymore defective.
	 * 
	 * <p>
	 * Use this method if there is good reason that a DLMS Component should be
	 * available again 'now', e.g. because it was turned on manually.
	 */
	public void retryDlmsCommunication();
}
