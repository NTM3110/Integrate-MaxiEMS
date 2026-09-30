package io.openems.edge.bms.service.calibration.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

public class InitialBaselineReferenceDatasetTest {

	@Test
	public void derivesPreciseImmutableCapacityLookupFromCutoffEvidence() {
		var now = Instant.parse("2026-09-10T00:00:00Z");
		var cutoff = new InitialBaselineReferenceDataset.CellCutoff(1, 70, 1.799, now, 181.123456);
		var dataset = new InitialBaselineReferenceDataset(42, UUID.randomUUID(), "str1", 1, 200, now, now,
				UUID.randomUUID(), null, null, null, Map.of(), List.of(cutoff), List.of());

		assertEquals(181.123456,
				dataset.cellCapacityReferences().get(1).measuredCellCapacityAh(), 0.0000001);
		assertThrows(UnsupportedOperationException.class,
				() -> dataset.cellCapacityReferences().put(2, dataset.cellCapacityReferences().get(1)));
	}

	@Test
	public void duplicateOrInvalidCapacityEvidenceIsRejected() {
		var now = Instant.parse("2026-09-10T00:00:00Z");
		var first = new InitialBaselineReferenceDataset.CellCutoff(1, 70, 1.8, now, 180);
		var duplicate = new InitialBaselineReferenceDataset.CellCutoff(1, 71, 1.79, now, 181);
		assertThrows(IllegalArgumentException.class,
				() -> dataset(now, List.of(first, duplicate)));

		var invalid = new InitialBaselineReferenceDataset.CellCutoff(1, 70, 1.8, now, 0);
		assertThrows(IllegalArgumentException.class, () -> dataset(now, List.of(invalid)));
	}

	private static InitialBaselineReferenceDataset dataset(Instant now,
			List<InitialBaselineReferenceDataset.CellCutoff> cutoffs) {
		return new InitialBaselineReferenceDataset(42, UUID.randomUUID(), "str1", 1, 200, now, now,
				UUID.randomUUID(), null, null, null, Map.of(), cutoffs, List.of());
	}
}
