package io.openems.edge.bridge.iec104.api.element;

import java.util.function.Consumer;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104Element represents one Information Object Address (IOA) of a
 * IEC 60870-5-104 Slave.
 * 
 * @param <T> the type of the value
 */
public abstract class Iec104Element<T> {

	private final int ioa;
	private final ASduType type;
	private Consumer<T> onUpdateCallback = null;

	public Iec104Element(int ioa, ASduType type) {
		this.ioa = ioa;
		this.type = type;
	}

	/**
	 * Gets the Information Object Address (IOA) of this Element.
	 * 
	 * @return the IOA
	 */
	public int getIoa() {
		return this.ioa;
	}

	/**
	 * Gets the ASDU Type Identification of this Element.
	 * 
	 * @return the {@link ASduType}
	 */
	public ASduType getType() {
		return this.type;
	}

	/**
	 * Registers a callback for when the value of this Element is updated.
	 * 
	 * @param onUpdateCallback the Callback
	 */
	public void onUpdateCallback(Consumer<T> onUpdateCallback) {
		this.onUpdateCallback = onUpdateCallback;
	}

	/**
	 * Called by the Protocol when a new ASDU containing this IOA is received.
	 * 
	 * @param value the new value
	 */
	public void setValue(T value) {
		if (this.onUpdateCallback != null) {
			this.onUpdateCallback.accept(value);
		}
	}
}
