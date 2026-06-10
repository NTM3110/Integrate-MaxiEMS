package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104SinglePointElement represents a Boolean value (e.g. M_SP_NA_1).
 */
public class Iec104SinglePointElement extends Iec104Element<Boolean> {

	public Iec104SinglePointElement(int ioa) {
		super(ioa, ASduType.M_SP_NA_1);
	}

}
