package io.openems.edge.bridge.iec104.api.element;

import org.openmuc.j60870.ASduType;

/**
 * An Iec104DoublePointTimeElement represents a Double Point value with time tag
 * (M_DP_TB_1, Type ID 31).
 * 
 * <p>
 * Double Point has 4 states: 0=Indeterminate/Intermediate, 1=OFF,
 * 2=ON, 3=Indeterminate.
 */
public class Iec104DoublePointTimeElement extends Iec104Element<Integer> {

	public Iec104DoublePointTimeElement(int ioa) {
		super(ioa, ASduType.M_DP_TB_1);
	}

}
