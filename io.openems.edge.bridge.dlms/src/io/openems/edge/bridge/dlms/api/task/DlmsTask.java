package io.openems.edge.bridge.dlms.api.task;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.bridge.dlms.api.DlmsComponent;
import io.openems.edge.bridge.dlms.api.element.DlmsElement;
import io.openems.edge.common.taskmanager.ManagedTask;

public interface DlmsTask extends ManagedTask {

	/**
	 * Gets the DlmsElements.
	 *
	 * @return an array of DlmsElements
	 */
	public DlmsElement[] getElements();

	/**
	 * Sets the parent.
	 *
	 * @param parent the parent {@link DlmsComponent}.
	 */
	public void setParent(DlmsComponent parent);

	/**
	 * Gets the parent.
	 *
	 * @return the parent
	 */
	public DlmsComponent getParent();

	/**
	 * This is called on deactivate of the DLMS-Bridge. It can be used to clear any
	 * references like listeners.
	 */
	public void deactivate();

	/**
	 * Executes the tasks - i.e. sends the query of a ReadTask.
	 *
	 * @param bridge the DLMS-Bridge
	 * @return {@link ExecuteState}
	 */
	public ExecuteState execute(AbstractDlmsBridge bridge);

	public interface ExecuteState {
		public static final class Ok implements ExecuteState {
			private Ok() {
			}
		}

		/** Successfully executed request(s). */
		public static final ExecuteState.Ok OK = new ExecuteState.Ok();

		/** Executing request(s) failed. */
		public static final record Error(Exception exception) implements ExecuteState {
		}
	}
}
