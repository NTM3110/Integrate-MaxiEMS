package io.openems.edge.bridge.dlms.api.element;

import java.util.function.Consumer;

import io.openems.edge.bridge.dlms.api.task.DlmsTask;

/**
 * A DLMS Element represents a single data point (OBIS code + attribute).
 */
public abstract class DlmsElement {

	private final String obis;
	private final int attributeIndex;
	private DlmsTask task = null;

	public DlmsElement(String obis, int attributeIndex) {
		this.obis = obis;
		this.attributeIndex = attributeIndex;
	}

	public String getObis() {
		return this.obis;
	}

	public int getAttributeIndex() {
		return this.attributeIndex;
	}

	public void setDlmsTask(DlmsTask task) {
		this.task = task;
	}

	public DlmsTask getDlmsTask() {
		return this.task;
	}

	/**
	 * Sets the value on this element.
	 *
	 * @param value the value
	 */
	public abstract void _setNextValue(Object value);

	/**
	 * This is called on deactivate of the DLMS-Bridge. It can be used to clear any
	 * references like listeners.
	 */
	public abstract void deactivate();
}
