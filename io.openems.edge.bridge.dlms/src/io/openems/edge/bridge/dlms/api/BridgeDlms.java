package io.openems.edge.bridge.dlms.api;

import java.util.Date;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.Debounce;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

@ProviderType
public interface BridgeDlms extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		CYCLE_TIME_IS_TOO_SHORT(Doc.of(Level.INFO) //
				.debounce(10, Debounce.TRUE_VALUES_IN_A_ROW_TO_SET_TRUE)), //
		/**
		 * Delay per Cycle before starting to execute DLMS Tasks. Global Cycle-Time can
		 * be reduced by this amount, without causing CYCLE_TIME_IS_TOO_SHORT.
		 */
		CYCLE_DELAY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.MILLISECONDS)),

		BRIDGE_IS_STOPPED(Doc.of(Level.INFO) //
				.text("DLMS Communication is stopped")) //
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
	 * Gets the Channel for {@link ChannelId#CYCLE_TIME_IS_TOO_SHORT}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getCycleTimeIsTooShortChannel() {
		return this.channel(ChannelId.CYCLE_TIME_IS_TOO_SHORT);
	}

	/**
	 * Gets the Cycle-Time-is-too-short State. See
	 * {@link ChannelId#CYCLE_TIME_IS_TOO_SHORT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getCycleTimeIsTooShort() {
		return this.getCycleTimeIsTooShortChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CYCLE_TIME_IS_TOO_SHORT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setCycleTimeIsTooShort(boolean value) {
		this.getCycleTimeIsTooShortChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CYCLE_DELAY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getCycleDelayChannel() {
		return this.channel(ChannelId.CYCLE_DELAY);
	}

	/**
	 * Gets the Cycle Delay in [ms], see {@link ChannelId#CYCLE_DELAY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getCycleDelay() {
		return this.getCycleDelayChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CYCLE_DELAY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setCycleDelay(long value) {
		this.getCycleDelayChannel().setNextValue(value);
	}

	/**
	 * Adds a Protocol with a source identifier to this DLMS Bridge.
	 *
	 * @param sourceId the unique source identifier
	 * @param protocol the DLMS Protocol
	 */
	public void addProtocol(String sourceId, DlmsProtocol protocol);

	/**
	 * Removes a Protocol from this DLMS Bridge.
	 *
	 * @param sourceId the unique source identifier
	 */
	public void removeProtocol(String sourceId);

	/**
	 * The DLMS Bridge marks defective Components, e.g. if there are communication
	 * failures. If a component is marked as defective, reads and writes are paused
	 * for an increasing waiting time. This method resets the waiting time, causing
	 * the DLMS Bridge to retry if a Component is not anymore defective.
	 * 
	 * @param sourceId the unique source identifier
	 */
	public void retryDlmsCommunication(String sourceId);

	/**
	 * Reads a Profile Generic object (history buffer) by date range.
	 *
	 * @param obis  the OBIS code of the ProfileGeneric object (e.g. "1.0.99.1.0.255")
	 * @param start range start (inclusive)
	 * @param end   range end   (inclusive)
	 * @return array of rows, each row is an Object[] of cell values
	 * @throws Exception on DLMS error
	 */
	Object[] readProfile(String obis, Date start, Date end) throws Exception;

	Object[] readBillingValues() throws Exception;

}
