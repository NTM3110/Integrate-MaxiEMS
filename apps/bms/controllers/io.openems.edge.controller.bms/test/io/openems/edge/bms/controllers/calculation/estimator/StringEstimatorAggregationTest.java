package io.openems.edge.bms.controllers.calculation.estimator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class StringEstimatorAggregationTest {

	@Test
	public void usesMinimumFiniteCellAndIgnoresUnavailableValues() {
		assertEquals(Double.valueOf(71d),
				StringEstimatorAggregation.minimumValid(new Double[] { 92d, null, 71d, Double.NaN }));
		assertNull(StringEstimatorAggregation.minimumValid(new Double[] { null, Double.NaN }));
	}
}
