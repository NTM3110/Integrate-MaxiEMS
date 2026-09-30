package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104BitstringElement represents a 32-bit Bitstring value (e.g.
 * M_BO_NA_1).
 * 
 * <p>
 * Bitstring of 32 bits is used for binary state information.
 */
public class Iec104BitstringElement extends Iec104Element<Integer> {

	public Iec104BitstringElement(int ioa) {
		super(ioa, ASduType.M_BO_NA_1);
	}

}
