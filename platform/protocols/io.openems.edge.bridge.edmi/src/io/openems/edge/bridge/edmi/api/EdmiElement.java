package io.openems.edge.bridge.edmi.api;

import java.util.function.Consumer;

/**
 * Defines an EDMI Element.
 */
public class EdmiElement {

	private final String address;
	private Consumer<Object> onUpdateCallback = (value) -> {
	};

	public EdmiElement(String address) {
		this.address = address;
	}

	public String getAddress() {
		return this.address;
	}

	/**
	 * Sets the callback for value updates.
	 * 
	 * @param onUpdateCallback the callback
	 */
	public void onUpdateCallback(Consumer<Object> onUpdateCallback) {
		this.onUpdateCallback = onUpdateCallback;
	}

	/**
	 * Called when a new value is received for this element.
	 * 
	 * @param value the new value
	 */
	public void _setNextValue(Object value) {
		this.onUpdateCallback.accept(value);
	}
}
