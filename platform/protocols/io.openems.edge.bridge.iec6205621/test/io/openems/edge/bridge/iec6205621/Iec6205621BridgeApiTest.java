package io.openems.edge.bridge.iec6205621;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Iec6205621BridgeApiTest {
	@Test
	public void serialDefaultsMatchA1700ModeC() {
		assertEquals(SerialParity.EVEN, SerialParity.valueOf("EVEN"));
		assertEquals(SerialStopBits.ONE, SerialStopBits.valueOf("ONE"));
	}
}
