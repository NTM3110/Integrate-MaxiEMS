package io.openems.edge.bms.service.calibration.implement;

import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.Test;

public class InitialBaselineCurveIntegrityTest {

	private static final UUID STRING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Test
	public void acceptsCanonicalCompleteCurve() {
		var header = header();
		var points = points();
		InitialBaselineCurveIntegrity.validatePublished(header, points.size(), endpoints(), points,
				InitialBaselineCurveIntegrity.contentHash(header, points));
	}

	@Test
	public void rejectsContentMutationWithOriginalHash() {
		var header = header();
		var points = points();
		var changed = List.of(points.get(0), point(2, 0, 201, 5.0, 10.0, 5.0, 2.19, false), points.get(2));
		assertThrows(IllegalStateException.class, () -> InitialBaselineCurveIntegrity.validatePublished(header,
				changed.size(), endpoints(), changed, InitialBaselineCurveIntegrity.contentHash(header, points)));
	}

	@Test
	public void rejectsNonContiguousPointOrderEvenWithMatchingHash() {
		var header = header();
		var points = List.of(point(1, 0, 101, 5.0, 10.0, 5.0, 2.20, false),
				point(1, 2, 102, 50.0, 100.0, 50.0, 1.80, true),
				point(2, 0, 202, 50.0, 100.0, 50.0, 1.79, true));
		assertThrows(IllegalStateException.class, () -> InitialBaselineCurveIntegrity.validatePublished(header,
				points.size(), endpoints(), points, InitialBaselineCurveIntegrity.contentHash(header, points)));
	}

	@Test
	public void rejectsMissingFinalCutoffEndpointEvenWithMatchingHash() {
		var header = header();
		var points = List.of(point(1, 0, 101, 5.0, 10.0, 5.0, 2.20, false),
				point(1, 1, 102, 50.0, 100.0, 50.0, 1.80, true),
				point(2, 0, 201, 5.0, 10.0, 5.0, 2.20, false));
		assertThrows(IllegalStateException.class, () -> InitialBaselineCurveIntegrity.validatePublished(header,
				points.size(), endpoints(), points, InitialBaselineCurveIntegrity.contentHash(header, points)));
	}

	private static InitialBaselineCurveIntegrity.Header header() {
		return new InitialBaselineCurveIntegrity.Header(1, 42, STRING_ID, 77, 0, 200.0, 50.0, 50.0, 2);
	}

	private static List<InitialBaselineCurveIntegrity.CellEndpoint> endpoints() {
		return List.of(new InitialBaselineCurveIntegrity.CellEndpoint(1, 102, 50.0),
				new InitialBaselineCurveIntegrity.CellEndpoint(2, 202, 50.0));
	}

	private static List<InitialBaselineCurveIntegrity.Point> points() {
		return List.of(point(1, 0, 101, 5.0, 10.0, 5.0, 2.20, false),
				point(1, 1, 102, 25.0, 100.0, 50.0, 1.80, true),
				point(2, 0, 202, 25.0, 100.0, 50.0, 1.79, true));
	}

	private static InitialBaselineCurveIntegrity.Point point(int cellIndex, int order, long sampleId,
			double milestonePercent, double normalizedPercent, double dischargedAh, double voltageV, boolean cutoff) {
		return new InitialBaselineCurveIntegrity.Point(cellIndex, order, sampleId, milestonePercent, normalizedPercent,
				dischargedAh, voltageV, 25.0, 1800, 80.0, 90.0, 240.0, 2.18, "VALID", true, cutoff);
	}
}
