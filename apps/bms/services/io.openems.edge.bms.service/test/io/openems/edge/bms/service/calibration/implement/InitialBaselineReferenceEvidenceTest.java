package io.openems.edge.bms.service.calibration.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calibration.implement.InitialBaselineCurveIntegrity.CellEndpoint;
import io.openems.edge.bms.service.calibration.implement.InitialBaselineCurveIntegrity.Header;
import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;
import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore.CurveSourcePoint;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

public class InitialBaselineReferenceEvidenceTest {

	@Test
	public void capacityCutoffAndVoltageCurveRemainCompleteWithoutSampleEstimates() {
		var points = CalibrationEventStore.withoutDerivedEstimates(List.of(
				point(10, 5, 10, 2.25, false), point(20, 90, 180, 1.80, true)));

		assertNull(points.get(0).cellSocPct());
		assertNull(points.get(0).cellSohPct());
		assertEquals(10d, points.get(0).dischargedAh(), 0d);
		assertEquals(2.25d, points.get(0).cellVoltageV(), 0d);
		assertEquals(25d, points.get(0).cellTemperatureC(), 0d);
		assertEquals(Integer.valueOf(1_800), points.get(0).cellResistanceUohm());
		assertEquals(247.5d, points.get(0).stringVoltageV(), 0d);
		assertEquals(2.25d, points.get(0).averageVoltageV(), 0d);
		assertEquals("VALID", points.get(0).quality());
		assertEquals(180d, points.get(0).cellUsableCapacityAh(), 0d);
		var integrity = CalibrationEventStore.integrityPoints(points);
		var header = new Header(1, 42, UUID.randomUUID(), 7, 0, 200, 180, 180, 1);
		var hash = InitialBaselineCurveIntegrity.contentHash(header, integrity);
		InitialBaselineCurveIntegrity.validatePublished(header, integrity.size(),
				List.of(new CellEndpoint(1, 20, 180)), integrity, hash);

		var now = Instant.parse("2026-09-10T00:00:00Z");
		var dataset = new InitialBaselineReferenceDataset(42, header.stringId(), "str1", 1, 200,
				now, now, UUID.randomUUID(), null, null, null, Map.of(),
				List.of(new InitialBaselineReferenceDataset.CellCutoff(1, 20, 1.80, now, 180)),
				List.of(new InitialBaselineReferenceDataset.CurvePoint(1, 0, 10, 5, 10d / 180d * 100d,
						10, 2.25, 25d, 1_800, null, null, 247.5, 2.25, "VALID", true),
						new InitialBaselineReferenceDataset.CurvePoint(1, 1, 20, 90, 100,
								180, 1.80, 25d, 1_800, null, null, 198d, 1.80, "VALID", true)));
		assertEquals(180d, dataset.cellCapacityReferences().get(1).measuredCellCapacityAh(), 0d);
	}

	@Test
	public void legacyAndNewCurvePayloadsAlignWithoutInventingEstimates() {
		var measured = CalibrationEventStore.integrityPoints(List.of(point(20, 90, 180, 1.80, true)));
		var newStored = CalibrationEventStore.integrityPoints(
				CalibrationEventStore.withoutDerivedEstimates(List.of(point(20, 90, 180, 1.80, true))));
		var alignedNew = InitialBaselineReferenceDatasetService.alignDeprecatedEstimates(newStored, measured);
		assertNull(alignedNew.get(0).cellSocPct());
		assertNull(alignedNew.get(0).cellSohPct());
		var header = new Header(1, 42, UUID.randomUUID(), 7, 0, 200, 180, 180, 1);
		var newHash = InitialBaselineCurveIntegrity.contentHash(header, newStored);
		InitialBaselineCurveIntegrity.validatePublished(header, newStored.size(),
				List.of(new CellEndpoint(1, 20, 180)), alignedNew, newHash);

		var alignedLegacy = InitialBaselineReferenceDatasetService.alignDeprecatedEstimates(measured, measured);
		assertEquals(10d, alignedLegacy.get(0).cellSocPct(), 0d);
		assertEquals(90d, alignedLegacy.get(0).cellSohPct(), 0d);
		var legacyHash = InitialBaselineCurveIntegrity.contentHash(header, measured);
		InitialBaselineCurveIntegrity.validatePublished(header, measured.size(),
				List.of(new CellEndpoint(1, 20, 180)), alignedLegacy, legacyHash);
	}

	private static CurveSourcePoint point(long sampleId, double milestone, double dischargedAh,
			double voltage, boolean cutoff) {
		return new CurveSourcePoint(1, sampleId, milestone, dischargedAh, voltage, 25d, 1_800,
				10d, 90d, voltage * 110, voltage, "VALID", 180d, cutoff);
	}
}
