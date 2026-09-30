package io.openems.edge.bridge.iec104.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.iec104.api.element.Iec104Element;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;

public abstract class AbstractOpenemsIec104Component extends AbstractOpenemsComponent
        implements Iec104Component, ComponentJsonApi {

    public static final String SEND_IEC104_COMMAND_METHOD = "sendIec104Command";

	private final Logger log = LoggerFactory.getLogger(AbstractOpenemsIec104Component.class);

	private Iec104Protocol protocol = null;
	private final Map<String, Iec104Element<?>> elementsByChannelId = new ConcurrentHashMap<>();

	protected AbstractOpenemsIec104Component(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	protected boolean activate(ComponentContext context, String id, String alias, boolean enabled,
			ConfigurationAdmin cm, String iec104Reference, String iec104Id) throws OpenemsException {
		super.activate(context, id, alias, enabled);
		return this.activateOrModified(cm, iec104Reference, iec104Id);
	}

	protected boolean modified(ComponentContext context, String id, String alias, boolean enabled,
			ConfigurationAdmin cm, String iec104Reference, String iec104Id) throws OpenemsException {
		super.modified(context, id, alias, enabled);
		return this.activateOrModified(cm, iec104Reference, iec104Id);
	}

	private boolean activateOrModified(ConfigurationAdmin cm, String iec104Reference, String iec104Id) {
		if (OpenemsComponent.updateReferenceFilter(cm, this.servicePid(), iec104Reference, iec104Id)) {
			return true;
		}
		var bridge = this.iec104Bridge.get();
		if (this.isEnabled() && bridge != null) {
			bridge.addProtocol(this.id(), this.getCommonAddress(), this.getIec104Protocol());
		}
		return false;
	}

	@Override
	protected void deactivate() {
		super.deactivate();
		var bridge = this.iec104Bridge.getAndSet(null);
		if (bridge != null) {
			bridge.removeProtocol(this.id());
		}
	}

	private final AtomicReference<Iec104Bridge> iec104Bridge = new AtomicReference<>(null);

	protected void setIec104Bridge(Iec104Bridge bridge) {
		this.iec104Bridge.set(bridge);
		if (this.isEnabled() && bridge != null) {
			bridge.addProtocol(this.id(), this.getCommonAddress(), this.getIec104Protocol());
		}
	}

	protected void unsetIec104Bridge(Iec104Bridge bridge) {
		this.iec104Bridge.compareAndSet(bridge, null);
		if (bridge != null) {
			bridge.removeProtocol(this.id());
		}
	}

	@Override
	public Iec104Protocol getIec104Protocol() {
		if (this.protocol == null) {
			this.protocol = this.defineIec104Protocol();
		}
		return this.protocol;
	}

	protected abstract Iec104Protocol defineIec104Protocol();

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(SEND_IEC104_COMMAND_METHOD, call -> {
			var request = call.getRequest();
			var params = request.getParams();
			var channelId = JsonUtils.getAsString(params, "channelId");
			var value = JsonUtils.getSubElement(params, "value");
			var select = JsonUtils.getAsOptionalBoolean(params, "select").orElse(false);

			this.sendIec104Command(channelId, value, select);

			var result = JsonUtils.buildJsonObject() //
					.addProperty("componentId", this.id()) //
					.addProperty("channelId", channelId) //
					.addProperty("select", select) //
					.build();
			return new GenericJsonrpcResponseSuccess(request.getId(), result);
		});
	}

	private void sendIec104Command(String channelId, JsonElement value, boolean select) throws Exception {
		var element = this.elementsByChannelId.get(channelId);
		if (element == null) {
			throw new OpenemsException("No IEC104 element mapped for channel [" + channelId + "]");
		}

		Channel<?> channel = this.channel(channelId);
		Object typedValue = JsonUtils.getAsType(channel.getType(), value);
		var command = this.getIec104Protocol().createWriteCommand(this.getCommonAddress(), element.getIoa(), typedValue,
				select);
		if (command == null) {
			throw new OpenemsException("Unsupported IEC104 command for channel [" + channelId + "] and value [" + value + "]");
		}

		var bridge = this.iec104Bridge.get();
		if (bridge == null) {
			throw new OpenemsException("IEC104 Bridge is not connected");
		}

		bridge.sendCommand(command);
	}

	/**
	 * Maps an Iec104Element to a Channel.
	 * 
	 * @param <T>       the Type
	 * @param channelId the ChannelId
	 * @param element   the Iec104Element
	 * @return the element
	 */
	protected final <T extends Iec104Element<?>> T m(io.openems.edge.common.channel.ChannelId channelId, T element) {
		this.elementsByChannelId.put(channelId.id(), element);
		Channel<?> channel = this.channel(channelId);
		element.onUpdateCallback(value -> {
			channel.setNextValue(value);
			this.log.info("Channel [{}] updated: {}", channelId.id(), value);
		});

		if (channel instanceof io.openems.edge.common.channel.WriteChannel<?> writeChannel) {
			writeChannel.onSetNextWrite(value -> {
				try {
					org.openmuc.j60870.ASdu command = this.getIec104Protocol().createWriteCommand(this.getCommonAddress(), element.getIoa(), value);
					if (command != null) {
						var bridge = this.iec104Bridge.get();
						if (bridge != null) {
							bridge.sendCommand(command);
						} else {
							this.logWarn(this.log, "Unable to send command: IEC 104 Bridge is not connected.");
						}
					}
				} catch (Exception e) {
					this.logWarn(this.log, "Unable to write to IEC104 Element [" + element.getIoa() + "]: " + e.getMessage());
				}
			});
		}

		return element;
	}

}
