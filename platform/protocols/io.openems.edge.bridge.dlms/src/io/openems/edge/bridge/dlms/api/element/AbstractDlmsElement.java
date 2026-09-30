package io.openems.edge.bridge.dlms.api.element;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.types.OpenemsType;

/**
 * A DlmsElement represents one OBIS object and attribute.
 *
 * @param <T> the value type
 */
public abstract class AbstractDlmsElement<T> extends DlmsElement {

	private final Logger log = LoggerFactory.getLogger(AbstractDlmsElement.class);
	private final List<Consumer<T>> onUpdateCallbacks = new CopyOnWriteArrayList<>();

	public final OpenemsType type;

	protected AbstractDlmsElement(OpenemsType type, String obis, int attributeIndex) {
		super(obis, attributeIndex);
		this.type = type;
	}

	@Override
	public final void _setNextValue(Object value) {
		T typedValue = this.convertToType(value);
		for (Consumer<T> callback : this.onUpdateCallbacks) {
			callback.accept(typedValue);
		}
	}

	protected abstract T convertToType(Object value);

	public final AbstractDlmsElement<T> onUpdateCallback(Consumer<T> onUpdateCallback) {
		this.onUpdateCallbacks.add(onUpdateCallback);
		return this;
	}

	@Override
	public void deactivate() {
		this.onUpdateCallbacks.clear();
	}
}
