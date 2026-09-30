package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104FloatElement represents a Float value (e.g. M_ME_NC_1).
 */
public class Iec104FloatElement extends Iec104Element<Float> {

	public Iec104FloatElement(int ioa) {
		super(ioa, ASduType.M_ME_NC_1);
	}

}
