package io.openems.edge.bridge.iec104;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.ClientConnectionBuilder;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.ie.IeQualifierOfInterrogation;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.iec104.api.AbstractIec104Bridge;
import io.openems.edge.bridge.iec104.api.Iec104Bridge;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Bridge.Iec104", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
		})
public class Iec104BridgeImpl extends AbstractIec104Bridge
		implements Iec104Bridge, OpenemsComponent, EventHandler, ConnectionEventListener {

	private final Logger log = LoggerFactory.getLogger(Iec104BridgeImpl.class);

	private Config config;
	private Connection connection = null;

	/** Counter for received ASDUs. */
	private final AtomicLong asduReceivedCount = new AtomicLong(0);

	public Iec104BridgeImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Iec104Bridge.ChannelId.values() //
		);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.cyclicInterrogationInterval());
		this.config = config;

		if (this.isEnabled()) {
			this.connect();
		}
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();

		this.disconnect();
	}

	private synchronized void connect() {
		if (this.connection != null) {
			return;
		}
		try {
			InetAddress address = InetAddress.getByName(this.config.ip());
			ClientConnectionBuilder builder = new ClientConnectionBuilder(address)
					.setPort(this.config.port())
					.setConnectionTimeout(this.config.connectionTimeout())
					.setConnectionEventListener(this);

			this.connection = builder.build();
			this.connection.startDataTransfer();

			this.logInfo(this.log, "Successfully connected to IEC 104 Slave [" + this.config.ip() + ":" + this.config.port() + "]");

			// Send initial General Interrogation to all known common addresses
			for (int commonAddress : this.getCommonAddresses()) {
				this.connection.interrogation(commonAddress, CauseOfTransmission.ACTIVATION,
						new IeQualifierOfInterrogation(20));
			}

			this.channel(Iec104Bridge.ChannelId.SLAVE_COMMUNICATION_FAILED).setNextValue(false);
			this.asduReceivedCount.set(0);
		} catch (Exception e) {
			this.logError(this.log, "Unable to connect to IEC 104 Slave: " + e.getMessage());
			this.channel(Iec104Bridge.ChannelId.SLAVE_COMMUNICATION_FAILED).setNextValue(true);
			this.disconnect();
		}
	}

	private synchronized void disconnect() {
		if (this.connection != null) {
			this.connection.close();
			this.connection = null;
		}
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.reconnectIfDisconnected();
			// Update ASDU count channel every cycle
			this.channel(Iec104Bridge.ChannelId.ASDU_RECEIVED_COUNT).setNextValue(this.asduReceivedCount.get());
			break;
		}
	}

	private void reconnectIfDisconnected() {
		if (this.connection == null) {
			this.connect();
		}
	}

	@Override
	public void newASdu(Connection connection, ASdu aSdu) {
		// Increment ASDU counter
		this.asduReceivedCount.incrementAndGet();

		// Log incoming data
		this.logInfo(this.log, "Received ASDU [" + aSdu.getTypeIdentification() + "] COT="
				+ aSdu.getCauseOfTransmission() + " #IOA=" + aSdu.getInformationObjects().length);

		this.handleASdu(aSdu);
	}

	@Override
	public void connectionClosed(Connection connection, IOException e) {
		this.logWarn(this.log, "IEC 104 Connection closed: " + (e != null ? e.getMessage() : "Unknown reason"));
		this.channel(Iec104Bridge.ChannelId.SLAVE_COMMUNICATION_FAILED).setNextValue(true);
		this.disconnect();
	}

	@Override
	public void dataTransferStateChanged(Connection connection, boolean stopped) {
		if (stopped) {
			this.logWarn(this.log, "Data transfer stopped.");
		} else {
			this.logInfo(this.log, "Data transfer started.");
		}
	}

	@Override
	public Connection getConnection() {
		return this.connection;
	}


	@Override
	public void sendCommand(ASdu command) throws OpenemsException {
		if (this.connection == null) {
			throw new OpenemsException("IEC 104 Connection is not established.");
		}
		try {
			this.connection.send(command);
		} catch (IOException e) {
			throw new OpenemsException("Failed to send command: " + e.getMessage());
		}
	}

	@Override
	public String debugLog() {
		var conn = this.connection != null ? "Connected" : "Disconnected";
		var count = this.asduReceivedCount.get();
		var commonAddressCount = this.getCommonAddresses().size();
		return "IEC104 " + conn + "|ASDU:" + count + "|CommonAddresses:" + commonAddressCount;
	}

}
