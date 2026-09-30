package io.openems.edge.bridge.dlms.api;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.dlms.api.element.AbstractDlmsElement;
import io.openems.edge.bridge.dlms.api.element.DlmsElement;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;

public abstract class AbstractOpenemsDlmsComponent extends AbstractOpenemsComponent implements DlmsComponent {

	private final Logger log = LoggerFactory.getLogger(AbstractOpenemsDlmsComponent.class);

	private DlmsProtocol protocol = null;
	private final AtomicReference<BridgeDlms> dlms = new AtomicReference<>(null);

	protected AbstractOpenemsDlmsComponent(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	protected boolean activate(ComponentContext context, String id, String alias, boolean enabled,
			ConfigurationAdmin cm, String dlmsReference, String dlmsId) throws OpenemsException {
		super.activate(context, id, alias, enabled);
		return this.activateOrModified(cm, dlmsReference, dlmsId);
	}

	protected boolean modified(ComponentContext context, String id, String alias, boolean enabled,
			ConfigurationAdmin cm, String dlmsReference, String dlmsId) throws OpenemsException {
		super.modified(context, id, alias, enabled);
		return this.activateOrModified(cm, dlmsReference, dlmsId);
	}

	private boolean activateOrModified(ConfigurationAdmin cm, String dlmsReference, String dlmsId) {
		if (OpenemsComponent.updateReferenceFilter(cm, this.servicePid(), dlmsReference, dlmsId)) {
			return true;
		}
		var dlms = this.dlms.get();
		if (this.isEnabled() && dlms != null) {
			dlms.addProtocol(this.id(), this.getDlmsProtocol());
			dlms.retryDlmsCommunication(this.id());
		}
		return false;
	}

	@Override
	protected void deactivate() {
		super.deactivate();
		var dlms = this.dlms.getAndSet(null);
		if (dlms != null) {
			dlms.removeProtocol(this.id());
		}
	}

	protected void setDlms(BridgeDlms dlms) {
		this.dlms.set(dlms);
	}

	protected void unsetDlms(BridgeDlms dlms) {
		this.dlms.compareAndSet(dlms, null);
		if (dlms != null) {
			dlms.removeProtocol(this.id());
		}
	}

	public BridgeDlms getBridgeDlms() {
		return this.dlms.get();
	}

	protected DlmsProtocol getDlmsProtocol() {
		if (this.protocol != null) {
			return this.protocol;
		}
		this.protocol = this.defineDlmsProtocol();
		return this.protocol;
	}

	protected void resetDlmsProtocol() {
		this.protocol = null;
	}

	@Override
	public void retryDlmsCommunication() {
		var bridge = this.dlms.get();
		if (bridge != null) {
			bridge.retryDlmsCommunication(this.id());
		}
	}

	protected abstract DlmsProtocol defineDlmsProtocol();

	/**
	 * Maps an Element to one or more DlmsChannels.
	 */
	public class ChannelMapper<ELEMENT extends DlmsElement> {

		private final ELEMENT element;
		private final Map<Channel<?>, Function<Object, Object>> channelMaps = new HashMap<>();

		public ChannelMapper(ELEMENT element) {
			this.element = element;
		}

		public ChannelMapper<ELEMENT> m(io.openems.edge.common.channel.ChannelId channelId) {
			Channel<?> channel = AbstractOpenemsDlmsComponent.this.channel(channelId);
			this.channelMaps.put(channel, value -> value);
			return this;
		}

		public ChannelMapper<ELEMENT> m(io.openems.edge.common.channel.ChannelId channelId,
				Function<Object, Object> converter) {
			Channel<?> channel = AbstractOpenemsDlmsComponent.this.channel(channelId);
			this.channelMaps.put(channel, converter);
			return this;
		}

		public ELEMENT build() {
			if (this.element instanceof AbstractDlmsElement<?> abstractElement) {
				abstractElement.onUpdateCallback(value -> {
					this.channelMaps.forEach((channel, converter) -> {
						Object convertedValue = converter.apply(value);
						channel.setNextValue(convertedValue);
					});
				});
			}
			return this.element;
		}
	}

	@FunctionalInterface
	public interface Function<T, R> {
		R apply(T t);
	}

	protected final <T extends DlmsElement> T m(io.openems.edge.common.channel.ChannelId channelId, T element) {
		return new ChannelMapper<>(element) //
				.m(channelId) //
				.build();
	}

	protected final <T extends DlmsElement> T m(io.openems.edge.common.channel.ChannelId channelId, T element,
			Function<Object, Object> converter) {
		return new ChannelMapper<>(element) //
				.m(channelId, converter) //
				.build();
	}
}
