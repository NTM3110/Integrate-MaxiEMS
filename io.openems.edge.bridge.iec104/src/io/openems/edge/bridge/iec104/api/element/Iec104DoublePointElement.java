package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104DoublePointElement represents a Double Point value (e.g.
 * M_DP_NA_1).
 * 
 * <p>
 * Double Point has 4 states: 0=Indeterminate/Intermediate, 1=OFF,
 * 2=ON, 3=Indeterminate.
 */
public class Iec104DoublePointElement extends Iec104Element<Integer> {

	public Iec104DoublePointElement(int ioa) {
		super(ioa, ASduType.M_DP_NA_1);
	}

}
