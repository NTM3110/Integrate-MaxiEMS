package io.openems.edge.bridge.iec104.api;

import java.util.Set;

import org.openmuc.j60870.Connection;

import io.openems.common.channel.Debounce;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface Iec104Bridge extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		SLAVE_COMMUNICATION_FAILED(Doc.of(Level.FAULT) //
				.debounce(10, Debounce.TRUE_VALUES_IN_A_ROW_TO_SET_TRUE)), //
		/**
		 * Number of ASDUs received since the last successful connection.
		 */
		ASDU_RECEIVED_COUNT(Doc.of(OpenemsType.LONG) //
				.unit(Unit.NONE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Number of ASDUs received")); //

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
	 * Gets the IEC 104 Connection.
	 * 
	 * @return the {@link Connection}
	 */
	public Connection getConnection();

	/**
	 * Gets the unique Common Addresses of ASDU from all active components.
	 * 
	 * @return a Set of common addresses
	 */
	public Set<Integer> getCommonAddresses();

	/**
	 * Adds an IEC 104 Protocol with a source identifier to this Bridge.
	 * 
	 * @param sourceId      the unique source identifier
	 * @param commonAddress the Common Address of ASDU
	 * @param protocol      the {@link Iec104Protocol}
	 */
	public void addProtocol(String sourceId, int commonAddress, Iec104Protocol protocol);

	/**
	 * Removes an IEC 104 Protocol from this Bridge.
	 * 
	 * @param sourceId the unique source identifier
	 */
	public void removeProtocol(String sourceId);

	/**
	 * Sends a Command ASDU to the connected IEC 104 slave.
	 * 
	 * @param command the Command {@link org.openmuc.j60870.ASdu}
	 * @throws io.openems.common.exceptions.OpenemsException on error
	 */
	public void sendCommand(org.openmuc.j60870.ASdu command) throws io.openems.common.exceptions.OpenemsException;

}
