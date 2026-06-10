package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104StepPositionElement represents a Step Position value (e.g.
 * M_ST_NA_1).
 * 
 * <p>
 * Step position is a 7-bit value (-64..63) with a transient state flag.
 */
public class Iec104StepPositionElement extends Iec104Element<Integer> {

	public Iec104StepPositionElement(int ioa) {
		super(ioa, ASduType.M_ST_NA_1);
	}

}
