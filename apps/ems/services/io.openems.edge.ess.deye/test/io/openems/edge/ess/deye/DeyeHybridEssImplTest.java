package io.openems.edge.ess.deye;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.pvinverter.deye.hybrid.PvInverterDeyeHybrid;

public class DeyeHybridEssImplTest {

	@Test
	public void testEssBundleBoundary() {
		assertEquals("io.openems.edge.ess.deye", DeyeHybridEssImpl.class.getPackageName());
		assertTrue(ManagedSymmetricEss.class.isAssignableFrom(DeyeHybridEssImpl.class));
		assertFalse(PvInverterDeyeHybrid.class.isAssignableFrom(DeyeHybridEssImpl.class));
		new DeyeHybridEssImpl();
	}

	@Test
	public void testPowerConversionsRemainCompatible() throws Exception {
		assertEquals(Integer.valueOf(26_000), DeyeHybridEssImpl.calculatePowerFromMilli(520_000, 50_000));
		assertNull(DeyeHybridEssImpl.calculatePowerFromMilli(null, 50_000));
		assertEquals(10_000, DeyeHybridEssImpl.resolvePowerLimit(0, 26_000, 10_000));
		assertEquals(5000, DeyeHybridEssImpl.resolvePowerLimit(5000, 26_000, 10_000));
		assertEquals(0, DeyeHybridEssImpl.resolvePowerLimit(0, null, 10_000));
		assertEquals(-500, DeyeHybridEssImpl.toRawPowerReference(-5000, 10_000, false));
		assertEquals(500, DeyeHybridEssImpl.toRawPowerReference(-5000, 10_000, true));
	}
}
