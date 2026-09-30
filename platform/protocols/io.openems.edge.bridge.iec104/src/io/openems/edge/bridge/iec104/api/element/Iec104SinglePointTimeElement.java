package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104SinglePointTimeElement represents a Boolean value with time tag
 * (M_SP_TB_1, Type ID 30).
 */
public class Iec104SinglePointTimeElement extends Iec104Element<Boolean> {

	public Iec104SinglePointTimeElement(int ioa) {
		super(ioa, ASduType.M_SP_TB_1);
	}

}
