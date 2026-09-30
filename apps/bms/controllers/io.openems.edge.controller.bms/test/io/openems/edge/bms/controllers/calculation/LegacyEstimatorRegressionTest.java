package io.openems.edge.bms.controllers.calculation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.EstimatorMode;
import io.openems.edge.bms.controllers.calculation.legacy.SoCEngine;
import io.openems.edge.bms.controllers.calculation.legacy.SoHEngine;

public class LegacyEstimatorRegressionTest {

	@Test
	public void legacyVoltageAndResistanceGoldenValuesRemainStable() {
		var soc = new SoCEngine(200, 1.8, 2.3);
		assertEquals(0d, soc.initialSoCFromVoltage(1.8), 0d);
		assertEquals(0.5d, soc.initialSoCFromVoltage(2.05), 0.0000001d);
		assertEquals(1d, soc.initialSoCFromVoltage(2.3), 0d);
		assertEquals(1d, SoHEngine.updatedSoHRegular(1_800, 1_800), 0d);
		assertEquals(0.7595721232d, SoHEngine.updatedSoHRegular(2_700, 1_800), 0.0000001d);
	}

	@Test
	public void autoIsDefaultAndLegacyRemainsExplicitRollback() {
		assertEquals(EstimatorMode.AUTO, EstimatorMode.parse(null));
		assertEquals(EstimatorMode.AUTO, EstimatorMode.parse(""));
		assertTrue(EstimatorMode.AUTO.baselineAware());
		assertEquals(EstimatorMode.LEGACY, EstimatorMode.parse("legacy"));
		assertFalse(EstimatorMode.LEGACY.baselineAware());
		assertEquals(EstimatorMode.BASELINE_V1, EstimatorMode.parse("BASELINE_V1"));
		assertTrue(EstimatorMode.BASELINE_V1.baselineAware());
		assertTrue(new SoCEngine(200, 1.8, 2.3).isUsableVoltage(2.3));
		assertFalse(new SoCEngine(200, 1.8, 2.3).isUsableVoltage(Double.NaN));
	}
}
