package io.openems.edge.bridge.dlms.api.task;

import io.openems.edge.bridge.dlms.api.DlmsComponent;
import io.openems.edge.bridge.dlms.api.element.DlmsElement;
import io.openems.edge.common.taskmanager.Priority;

/**
 * An abstract DLMS 'Task' is holding references to one or more DLMS
 * {@link DlmsElement}s.
 */
public abstract class AbstractDlmsTask implements DlmsTask {

	private final Priority priority;
	private final DlmsElement[] elements;
	private DlmsComponent parent = null;

	public AbstractDlmsTask(Priority priority, DlmsElement... elements) {
		this.priority = priority;
		this.elements = elements;
		for (DlmsElement element : elements) {
			element.setDlmsTask(this);
		}
	}

	@Override
	public Priority getPriority() {
		return this.priority;
	}

	@Override
	public DlmsElement[] getElements() {
		return this.elements;
	}

	@Override
	public void setParent(DlmsComponent parent) {
		this.parent = parent;
	}

	@Override
	public DlmsComponent getParent() {
		return this.parent;
	}

	@Override
	public void deactivate() {
		for (DlmsElement element : this.elements) {
			element.deactivate();
		}
	}
}
