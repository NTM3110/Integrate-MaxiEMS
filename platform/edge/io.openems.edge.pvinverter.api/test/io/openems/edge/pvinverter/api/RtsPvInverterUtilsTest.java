package io.openems.edge.pvinverter.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.pvinverter.api.RtsPvInverterUtils.CalculationStatus;

public class RtsPvInverterUtilsTest {

	@Test
	public void testDcPower() {
		var result = RtsPvInverterUtils.calculateDcPower(600_000, 12_345);
		assertEquals(CalculationStatus.VALID, result.status());
		assertEquals(Integer.valueOf(7_407), result.value());

		assertEquals(CalculationStatus.UNAVAILABLE, RtsPvInverterUtils.calculateDcPower(null, 100).status());
		assertEquals(CalculationStatus.INVALID, RtsPvInverterUtils.calculateDcPower(-1, 100).status());
		assertEquals(CalculationStatus.INVALID,
				RtsPvInverterUtils.calculateDcPower(Integer.MAX_VALUE, Integer.MAX_VALUE).status());
	}

	@Test
	public void testPowerFactor() {
		assertEquals(Integer.valueOf(800), RtsPvInverterUtils.calculatePowerFactor(8_000, 6_000, 10_000).value());
		assertEquals(Integer.valueOf(-800), RtsPvInverterUtils.calculatePowerFactor(8_000, -6_000, 10_000).value());
		assertEquals(CalculationStatus.UNAVAILABLE,
				RtsPvInverterUtils.calculatePowerFactor(0, 0, 0).status());
		assertEquals(CalculationStatus.INVALID,
				RtsPvInverterUtils.calculatePowerFactor(11_000, 0, 10_000).status());
	}

	@Test
	public void testAggregateAndScaleValidation() {
		assertEquals(Integer.valueOf(30), RtsPvInverterUtils.sumAvailable(10, null, 20).value());
		assertEquals(Integer.valueOf(15), RtsPvInverterUtils.averageAvailable(10, null, 20).value());
		assertEquals(CalculationStatus.UNAVAILABLE, RtsPvInverterUtils.sumAvailable(null, null).status());
		assertEquals(CalculationStatus.INVALID,
				RtsPvInverterUtils.sumAvailable(Integer.MAX_VALUE, 1).status());

		assertEquals(Integer.valueOf(12_300), RtsPvInverterUtils.scaleToInteger(123L, 100, 1).value());
		assertTrue(RtsPvInverterUtils.scaleToInteger(0xFFFFL, 1, 1, 0xFFFFL).isInvalid());
		assertTrue(RtsPvInverterUtils.scaleToInteger(Long.MAX_VALUE, 2, 1).isInvalid());
	}
}
