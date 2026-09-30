package io.openems.edge.bms.service.calibration.implement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared canonical hash and structural validation for an Initial Baseline curve. */
public final class InitialBaselineCurveIntegrity {

	private static final double EPSILON = 1e-6;

	private InitialBaselineCurveIntegrity() {
	}

	public static String contentHash(Header header, List<Point> points) {
		var canonical = new StringBuilder("curve-schema=").append(header.schemaVersion()).append('\n')
				.append("event=").append(header.eventId()).append('\n')
				.append("string=").append(header.stringId()).append('\n')
				.append("process=").append(header.processId()).append(':').append(header.processAttemptNumber()).append('\n')
				.append("capacity-reference=").append(Double.toHexString(header.capacityReferenceAh())).append('\n')
				.append("safe-capacity=").append(Double.toHexString(header.stringSafeUsableCapacityAh())).append('\n')
				.append("all-cells-capacity=").append(Double.toHexString(header.allCellsEndpointAh())).append('\n')
				.append("cell-count=").append(header.cellCount()).append('\n');
		for (var point : points) {
			canonical.append(point.cellIndex()).append('|').append(point.sampleId()).append('|')
					.append(Double.toHexString(point.milestonePercent())).append('|')
					.append(Double.toHexString(point.dischargedAh())).append('|')
					.append(Double.toHexString(point.cellVoltageV())).append('|')
					.append(point.cellTemperatureC()).append('|').append(point.cellResistanceUohm()).append('|')
					.append(point.cellSocPct()).append('|').append(point.cellSohPct()).append('|')
					.append(point.stringVoltageV()).append('|').append(point.averageVoltageV()).append('|')
					.append(point.quality()).append('|').append(point.cutoff()).append('\n');
		}
		return sha256(canonical.toString());
	}

	public static void validatePublished(Header header, int declaredPointCount, List<CellEndpoint> endpoints,
			List<Point> points, String expectedHash) {
		validatePublished(header, declaredPointCount, endpoints, points, points, expectedHash);
	}

	public static void validatePublished(Header header, int declaredPointCount, List<CellEndpoint> endpoints,
			List<Point> points, List<Point> hashSourcePoints, String expectedHash) {
		if (header.schemaVersion() != 1) throw invalid("Unsupported Initial Baseline curve schema");
		if (header.eventId() <= 0 || header.stringId() == null || header.processId() <= 0
				|| header.processAttemptNumber() < 0 || header.cellCount() <= 0) {
			throw invalid("Invalid Initial Baseline curve identity");
		}
		requirePositiveFinite(header.capacityReferenceAh(), "capacity reference");
		requirePositiveFinite(header.stringSafeUsableCapacityAh(), "safe usable capacity");
		requirePositiveFinite(header.allCellsEndpointAh(), "all-cells endpoint");
		if (declaredPointCount != points.size() || points.isEmpty()) {
			throw invalid("Published curve point count does not match stored points");
		}
		if (endpoints.size() != header.cellCount()) {
			throw invalid("Published curve does not cover every configured cell cutoff");
		}

		var endpointByCell = new HashMap<Integer, CellEndpoint>();
		for (int index = 0; index < endpoints.size(); index++) {
			var endpoint = endpoints.get(index);
			if (endpoint.cellIndex() != index + 1 || endpointByCell.put(endpoint.cellIndex(), endpoint) != null) {
				throw invalid("Curve cutoff cell indexes are not contiguous");
			}
			requirePositiveFinite(endpoint.usableCapacityAh(), "cell usable capacity");
			if (endpoint.sampleId() <= 0) throw invalid("Curve cutoff sample id must be positive");
		}

		var nextOrder = new HashMap<Integer, Integer>();
		var lastDischargedAh = new HashMap<Integer, Double>();
		var lastPoint = new HashMap<Integer, Point>();
		var cutoffCount = new HashMap<Integer, Integer>();
		for (var point : points) {
			var endpoint = endpointByCell.get(point.cellIndex());
			if (endpoint == null) throw invalid("Curve point references an unknown cell");
			int expectedOrder = nextOrder.getOrDefault(point.cellIndex(), 0);
			if (point.pointOrder() != expectedOrder) throw invalid("Curve point order is not contiguous");
			nextOrder.put(point.cellIndex(), expectedOrder + 1);
			if (point.sampleId() <= 0 || !point.usable() || !"VALID".equals(point.quality())) {
				throw invalid("Curve contains an unusable or invalid point");
			}
			requireNonNegativeFinite(point.milestonePercent(), "rated DOD milestone");
			requireNonNegativeFinite(point.normalizedActualDodPercent(), "normalized actual DOD");
			requireNonNegativeFinite(point.dischargedAh(), "discharged capacity");
			requirePositiveFinite(point.cellVoltageV(), "cell voltage");
			var previous = lastDischargedAh.put(point.cellIndex(), point.dischargedAh());
			lastPoint.put(point.cellIndex(), point);
			if (previous != null && point.dischargedAh() + EPSILON < previous) {
				throw invalid("Curve discharged capacity is not monotonic");
			}
			double normalized = point.dischargedAh() / endpoint.usableCapacityAh() * 100.0;
			if (Math.abs(normalized - point.normalizedActualDodPercent()) > EPSILON
					|| point.normalizedActualDodPercent() > 100.0 + EPSILON) {
				throw invalid("Curve normalized DOD is inconsistent with the cell endpoint");
			}
			if (point.cutoff()) cutoffCount.merge(point.cellIndex(), 1, Integer::sum);
		}
		for (int cellIndex = 1; cellIndex <= header.cellCount(); cellIndex++) {
			var endpoint = endpointByCell.get(cellIndex);
			if (nextOrder.getOrDefault(cellIndex, 0) == 0 || cutoffCount.getOrDefault(cellIndex, 0) != 1) {
				throw invalid("Curve requires exactly one cutoff endpoint per cell");
			}
			var last = lastPoint.get(cellIndex);
			if (!last.cutoff() || last.sampleId() != endpoint.sampleId()
					|| Math.abs(last.dischargedAh() - endpoint.usableCapacityAh()) > EPSILON
					|| Math.abs(last.normalizedActualDodPercent() - 100.0) > EPSILON) {
				throw invalid("Curve final point does not match the cell cutoff endpoint");
			}
		}
		var actualHash = contentHash(header, hashSourcePoints);
		if (expectedHash == null || expectedHash.isBlank() || !expectedHash.equals(actualHash)) {
			throw invalid("Published Initial Baseline curve content hash mismatch: expected=" + expectedHash
					+ ", actual=" + actualHash);
		}
	}

	private static void requirePositiveFinite(double value, String name) {
		if (!Double.isFinite(value) || value <= 0) throw invalid("Invalid " + name);
	}

	private static void requireNonNegativeFinite(double value, String name) {
		if (!Double.isFinite(value) || value < 0) throw invalid("Invalid " + name);
	}

	private static String sha256(String value) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			var result = new StringBuilder(digest.length * 2);
			for (var item : digest) result.append(String.format("%02x", item));
			return result.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static IllegalStateException invalid(String message) {
		return new IllegalStateException(message);
	}

	public record Header(int schemaVersion, long eventId, UUID stringId, long processId, int processAttemptNumber,
			double capacityReferenceAh, double stringSafeUsableCapacityAh, double allCellsEndpointAh, int cellCount) {
	}

	public record CellEndpoint(int cellIndex, long sampleId, double usableCapacityAh) {
	}

	public record Point(int cellIndex, int pointOrder, long sampleId, double milestonePercent,
			double normalizedActualDodPercent, double dischargedAh, double cellVoltageV,
			Double cellTemperatureC, Integer cellResistanceUohm, Double cellSocPct, Double cellSohPct,
			Double stringVoltageV, Double averageVoltageV, String quality, boolean usable, boolean cutoff) {
	}
}
