package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104NormalizedValueElement represents a Normalized Value (e.g.
 * M_ME_NA_1).
 * 
 * <p>
 * The normalized value is a floating point value in the range -1.0 to +1.0.
 */
public class Iec104NormalizedValueElement extends Iec104Element<Float> {

	public Iec104NormalizedValueElement(int ioa) {
		super(ioa, ASduType.M_ME_NA_1);
	}

}
