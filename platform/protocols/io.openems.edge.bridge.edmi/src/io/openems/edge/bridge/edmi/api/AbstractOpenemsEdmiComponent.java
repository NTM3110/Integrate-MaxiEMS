package io.openems.edge.bridge.edmi.api;

import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;

public abstract class AbstractOpenemsEdmiComponent extends AbstractOpenemsComponent {

	private final AtomicReference<EdmiBridge> bridge = new AtomicReference<>();
	private EdmiProtocol protocol = null;

	protected AbstractOpenemsEdmiComponent(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	protected void activate(ComponentContext context, String id, String alias, boolean enabled, EdmiBridge bridge) {
		super.activate(context, id, alias, enabled);
		this.bridge.set(bridge);
		if (this.isEnabled() && bridge != null) {
			this.protocol = this.defineEdmiProtocol();
			for (EdmiTask task : this.protocol.getTasks()) {
				bridge.addTask(task);
			}
		}
	}

	@Override
	protected void deactivate() {
		var bridge = this.bridge.get();
		if (bridge != null && this.protocol != null) {
			for (EdmiTask task : this.protocol.getTasks()) {
				bridge.removeTask(task);
			}
		}
		super.deactivate();
	}

	protected abstract EdmiProtocol defineEdmiProtocol();

	/**
	 * Maps an EdmiAddress to an OpenEMS Channel with a converter.
	 * 
	 * @param channelId the OpenEMS Channel-ID
	 * @param address   the EDMI Address/Command
	 * @param converter the value converter
	 * @return the {@link EdmiElement}
	 */
	protected EdmiElement m(io.openems.edge.common.channel.ChannelId channelId, String address,
			java.util.function.Function<Object, Object> converter) {
		EdmiElement element = new EdmiElement(address);
		Channel<?> channel = this.channel(channelId);
		element.onUpdateCallback(value -> {
			channel.setNextValue(converter.apply(value));
		});
		return element;
	}

	/**
	 * Maps an EdmiAddress to an OpenEMS Channel without conversion.
	 * 
	 * @param channelId the OpenEMS Channel-ID
	 * @param address   the EDMI Address/Command
	 * @return the {@link EdmiElement}
	 */
	protected EdmiElement m(io.openems.edge.common.channel.ChannelId channelId, String address) {
		return this.m(channelId, address, value -> value);
	}
}
