package io.openems.edge.bridge.iec104.api;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.Connection;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;

import io.openems.edge.bridge.iec104.api.worker.Iec104Worker;
import io.openems.edge.common.component.AbstractOpenemsComponent;

public abstract class AbstractIec104Bridge extends AbstractOpenemsComponent implements Iec104Bridge, EventHandler {

	private final Map<String, ProtocolRegistration> protocols = new ConcurrentHashMap<>();
	protected Iec104Worker worker;

	protected AbstractIec104Bridge(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	protected void activate(ComponentContext context, String id, String alias, boolean enabled,
			int cyclicInterrogationInterval) {
		super.activate(context, id, alias, enabled);
		if (enabled) {
			this.worker = new Iec104Worker(this, cyclicInterrogationInterval);
			this.worker.activate(id);
		}
	}

	@Override
	public void handleEvent(Event event) {
	}

	@Override
	protected void deactivate() {
		super.deactivate();
		if (this.worker != null) {
			this.worker.deactivate();
			this.worker = null;
		}
	}

	@Override
	public void addProtocol(String sourceId, int commonAddress, Iec104Protocol protocol) {
		this.protocols.put(sourceId, new ProtocolRegistration(commonAddress, protocol));
	}

	@Override
	public void removeProtocol(String sourceId) {
		this.protocols.remove(sourceId);
	}

	@Override
	public Set<Integer> getCommonAddresses() {
		return this.protocols.values().stream() //
				.map(ProtocolRegistration::commonAddress) //
				.collect(Collectors.toSet());
	}

	protected void handleASdu(ASdu aSdu) {
		var commonAddress = aSdu.getCommonAddress();
		this.protocols.values().stream() //
				.filter(registration -> registration.commonAddress() == commonAddress) //
				.map(ProtocolRegistration::protocol) //
				.forEach(protocol -> protocol.handleAsdu(aSdu));
	}

	@Override
	public abstract Connection getConnection();

	private record ProtocolRegistration(int commonAddress, Iec104Protocol protocol) {
	}
}
