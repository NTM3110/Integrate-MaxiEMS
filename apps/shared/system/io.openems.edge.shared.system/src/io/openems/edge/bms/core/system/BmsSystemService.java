package io.openems.edge.bms.core.system;

import io.openems.common.exceptions.OpenemsException;

/** Executes the limited privileged system actions approved for the BMS UI. */
public interface BmsSystemService {

	/** Requests a non-blocking operating-system reboot. */
	public void reboot() throws OpenemsException;
}
