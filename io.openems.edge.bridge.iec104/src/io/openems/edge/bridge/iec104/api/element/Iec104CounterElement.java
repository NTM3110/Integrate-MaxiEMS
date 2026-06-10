package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104CounterElement represents a Binary Counter Reading (e.g.
 * M_IT_NA_1).
 * 
 * <p>
 * Counter reading is a 32-bit signed integer value with sequence number,
 * carry flag, and valid flag.
 */
public class Iec104CounterElement extends Iec104Element<Long> {

	public Iec104CounterElement(int ioa) {
		super(ioa, ASduType.M_IT_NA_1);
	}

}
