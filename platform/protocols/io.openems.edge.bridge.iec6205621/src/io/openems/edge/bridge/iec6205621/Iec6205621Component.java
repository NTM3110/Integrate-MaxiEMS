package io.openems.edge.bridge.iec6205621;

import io.openems.edge.common.component.OpenemsComponent;

/** A component that communicates through an IEC 62056-21 Bridge. */
public interface Iec6205621Component extends OpenemsComponent {

	/** Updates the component communication-failed state. */
	void _setIec6205621CommunicationFailed(boolean value);
}
