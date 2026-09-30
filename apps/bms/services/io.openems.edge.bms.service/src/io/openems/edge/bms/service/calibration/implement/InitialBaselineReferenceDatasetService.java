package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonParser;

import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset.CellCutoff;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset.CellMeasurement;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset.CurvePoint;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset.CurveVersion;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset.Evidence;
import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset.PersistedSnapshot;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Loads one coherent, approved Initial Baseline reference from PostgreSQL. */
@Component(name = "io.openems.edge.bms.platform.calibration.InitialBaselineReferenceDatasetService", service = InitialBaselineReferenceDatasetService.class)
public class InitialBaselineReferenceDatasetService {

	private static final List<String> REQUIRED_EVIDENCE = List.of("CHARGER_OUTPUT_CURRENT", "MIDPOINT_VOLTAGE",
			"RIPPLE_BEFORE_LOAD", "RIPPLE_AFTER_LOAD");

	@Reference
	private BmsDatabase database;

	public InitialBaselineReferenceDataset loadApprovedByEventId(long eventId) {
		if (eventId <= 0) {
			throw new IllegalArgumentException("eventId must be positive");
		}
		return this.read(connection -> load(connection, eventId, null));
	}

	public Optional<InitialBaselineReferenceDataset> loadActiveByStringId(UUID stringId) {
		if (stringId == null) {
			throw new IllegalArgumentException("stringId is required");
		}
		return this.read(connection -> {
			try (var query = connection.prepareStatement("""
				SELECT calibration_event_id,curve_version_id
				FROM bms.calibration_active_baseline WHERE string_id=?
				""")) {
				query.setObject(1, stringId);
				try (var result = query.executeQuery()) {
					if (!result.next()) {
						return Optional.empty();
					}
					return Optional.of(load(connection, result.getLong(1), result.getLong(2)));
				}
			}
		});
	}

	public Optional<InitialBaselineReferenceDataset> loadActiveByStringKey(String stringKey) {
		if (stringKey == null || stringKey.isBlank()) {
			throw new IllegalArgumentException("stringKey is required");
		}
		return this.read(connection -> {
			try (var query = connection.prepareStatement("""
				SELECT active.calibration_event_id,active.curve_version_id
				FROM bms.calibration_active_baseline active
				JOIN bms.bms_string string ON string.id=active.string_id
				WHERE string.string_key=?
				""")) {
				query.setString(1, stringKey);
				try (var result = query.executeQuery()) {
					if (!result.next()) return Optional.empty();
					return Optional.of(load(connection, result.getLong(1), result.getLong(2)));
				}
			}
		});
	}

	public List<InitialBaselineReferenceDataset> loadAllActive() {
		return this.read(connection -> {
			var values = new ArrayList<InitialBaselineReferenceDataset>();
			try (var query = connection.prepareStatement("""
				SELECT calibration_event_id,curve_version_id
				FROM bms.calibration_active_baseline ORDER BY string_id
				"""); var result = query.executeQuery()) {
				while (result.next()) values.add(load(connection, result.getLong(1), result.getLong(2)));
			}
			return List.copyOf(values);
		});
	}

	private InitialBaselineReferenceDataset load(Connection connection, long eventId, Long requiredCurveId)
			throws SQLException {
		EventRow event;
		try (var query = connection.prepareStatement("""
			SELECT event.id,event.string_id,string.string_key,event.configuration_snapshot,
				event.completed_at,event.baseline_approved_at,event.baseline_approved_by
			FROM bms.calibration_event event
			JOIN bms.bms_string string ON string.id=event.string_id
			WHERE event.id=? AND event.event_type='INITIAL_BASELINE'
				AND event.status='COMPLETED' AND event.baseline_approved_at IS NOT NULL
			""")) {
			query.setLong(1, eventId);
			try (var result = query.executeQuery()) {
				if (!result.next()) {
					throw invalid("Event is not an approved, completed INITIAL_BASELINE: " + eventId);
				}
				var config = JsonParser.parseString(result.getString(4)).getAsJsonObject();
				event = new EventRow(result.getLong(1), result.getObject(2, UUID.class), result.getString(3),
						requiredPositiveInt(config, "cellCount"), requiredPositive(config, "ratedCapacityAh"),
						requiredPositive(config, "capacityReferenceAh"),
						instant(result, 5), instant(result, 6), result.getObject(7, UUID.class));
			}
		}

		var curve = loadCurve(connection, event, requiredCurveId);
		if (event.completedAt() == null || event.approvedAt() == null || event.approvedBy() == null) {
			throw invalid("Approved Initial Baseline is missing completion/approval identity");
		}
		var cutoffs = loadCutoffs(connection, event, curve);
		var points = loadCurvePoints(connection, curve, cutoffs);
		validateCurve(connection, event, curve, cutoffs, points);
		var recovery = loadSnapshot(connection, event, "RECOVERY_FLOAT_STABILIZATION", true);
		var voc = loadSnapshot(connection, event, "VOC_BASELINE_CAPTURE", false);
		var evidence = loadEvidence(connection, event, recovery, voc);
		return new InitialBaselineReferenceDataset(event.id(), event.stringId(), event.stringKey(), event.cellCount(),
				event.ratedCapacityAh(), event.completedAt(), event.approvedAt(), event.approvedBy(), curve, recovery,
				voc, evidence, cutoffs, points);
	}

	private static CurveVersion loadCurve(Connection connection, EventRow event, Long requiredCurveId)
			throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT curve.id,curve.calibration_process_id,curve.process_attempt_number,
				curve.curve_schema_version,curve.capacity_reference_ah,
				curve.string_safe_usable_capacity_ah,curve.all_cells_endpoint_ah,curve.cell_count,
				curve.point_count,curve.content_hash,curve.published_at
			FROM bms.calibration_curve_version curve
			WHERE curve.calibration_event_id=? AND curve.string_id=?
				AND (?::bigint IS NULL OR curve.id=?)
			ORDER BY curve.published_at DESC,curve.id DESC LIMIT 1
			""")) {
			query.setLong(1, event.id());
			query.setObject(2, event.stringId());
			if (requiredCurveId == null) {
				query.setNull(3, java.sql.Types.BIGINT);
				query.setNull(4, java.sql.Types.BIGINT);
			} else {
				query.setLong(3, requiredCurveId);
				query.setLong(4, requiredCurveId);
			}
			try (var result = query.executeQuery()) {
				if (!result.next()) {
					throw invalid("Approved Initial Baseline has no matching published curve");
				}
				if (result.getString(10) == null || result.getString(10).isBlank()) {
					throw invalid("Published curve has no content hash");
				}
				return new CurveVersion(result.getLong(1), result.getLong(2), result.getInt(3), result.getInt(4),
						result.getDouble(5), result.getDouble(6), result.getDouble(7), result.getInt(8),
						result.getInt(9), result.getString(10), instant(result, 11));
			}
		}
	}

	private static PersistedSnapshot loadSnapshot(Connection connection, EventRow event, String processType,
			boolean requireResistance) throws SQLException {
		long sampleId;
		long processId;
		int attempt;
		UUID generation;
		long sequence;
		Instant measuredAt;
		Double rawCurrent;
		Double domainCurrent;
		Double stringVoltage;
		Double midpoint;
		Double averageVoltage;
		Double ambient;
		Double averageTemperature;
		try (var query = connection.prepareStatement("""
			SELECT sample.id,process.id,process.attempt_number,sample.snapshot_generation,
				sample.snapshot_sequence,sample.measurement_time,sample.raw_current_a,sample.domain_current_a,
				sample.string_voltage_v,sample.midpoint_voltage_v,sample.average_voltage_v,
				sample.ambient_temperature_c,sample.average_cell_temperature_c
			FROM bms.calibration_process process
			JOIN bms.calibration_sample sample ON sample.calibration_process_id=process.id
				AND sample.process_attempt_number=process.attempt_number
			WHERE process.calibration_event_id=? AND process.process_type=? AND process.state='COMPLETED'
				AND sample.persistence_reason='FINAL_SNAPSHOT' AND sample.quality='VALID'
			ORDER BY sample.measurement_time DESC,sample.id DESC LIMIT 1
			""")) {
			query.setLong(1, event.id());
			query.setString(2, processType);
			try (var result = query.executeQuery()) {
				if (!result.next()) {
					throw invalid("Missing canonical FINAL_SNAPSHOT for " + processType);
				}
				sampleId = result.getLong(1);
				processId = result.getLong(2);
				attempt = result.getInt(3);
				generation = result.getObject(4, UUID.class);
				sequence = result.getLong(5);
				measuredAt = instant(result, 6);
				rawCurrent = nullableDouble(result, 7);
				domainCurrent = nullableDouble(result, 8);
				stringVoltage = nullableDouble(result, 9);
				midpoint = nullableDouble(result, 10);
				averageVoltage = nullableDouble(result, 11);
				ambient = nullableDouble(result, 12);
				averageTemperature = nullableDouble(result, 13);
			}
		}
		var cells = loadCells(connection, sampleId, event.cellCount(), requireResistance);
		if (generation == null || measuredAt == null || stringVoltage == null || domainCurrent == null
				|| requireResistance && (midpoint == null || averageVoltage == null)) {
			throw invalid("FINAL_SNAPSHOT is missing required string measurements for " + processType);
		}
		return new PersistedSnapshot(sampleId, processId, attempt, generation, sequence, measuredAt, rawCurrent,
				domainCurrent, stringVoltage, midpoint, averageVoltage, ambient, averageTemperature, cells);
	}

	private static List<CellMeasurement> loadCells(Connection connection, long sampleId, int expectedCount,
			boolean requireResistance) throws SQLException {
		var cells = new ArrayList<CellMeasurement>();
		try (var query = connection.prepareStatement("""
			SELECT cell_index,voltage_v,temperature_c,resistance_uohm,soc_pct,soh_pct,quality,availability_warning
			FROM bms.calibration_cell_sample WHERE calibration_sample_id=? ORDER BY cell_index
			""")) {
			query.setLong(1, sampleId);
			try (var result = query.executeQuery()) {
				while (result.next()) {
					var resistance = result.getObject(4, Integer.class);
					if (result.getInt(1) != cells.size() + 1 || nullableDouble(result, 2) == null
							|| !"VALID".equals(result.getString(7)) || result.getString(8) != null
							|| requireResistance && resistance == null) {
						throw invalid("Invalid cell coverage in FINAL_SNAPSHOT " + sampleId);
					}
					cells.add(new CellMeasurement(result.getInt(1), nullableDouble(result, 2), nullableDouble(result, 3),
							resistance, nullableDouble(result, 5), nullableDouble(result, 6), result.getString(7),
							result.getString(8)));
				}
			}
		}
		if (cells.size() != expectedCount) {
			throw invalid("FINAL_SNAPSHOT cell coverage is " + cells.size() + "/" + expectedCount);
		}
		return List.copyOf(cells);
	}

	private static Map<String, Evidence> loadEvidence(Connection connection, EventRow event,
			PersistedSnapshot recovery, PersistedSnapshot voc) throws SQLException {
		var values = new LinkedHashMap<String, Evidence>();
		try (var query = connection.prepareStatement("""
			SELECT DISTINCT ON (evidence_type) id,evidence_type,status,numeric_value,unit,
				calibration_sample_id,recorded_at
			FROM bms.calibration_manual_evidence
			WHERE calibration_event_id=? AND evidence_type=ANY(?) AND status IN ('PASS','RECORDED')
				AND recorded_at>=? AND recorded_at<=?
			ORDER BY evidence_type,recorded_at DESC,id DESC
			""")) {
			query.setLong(1, event.id());
			query.setArray(2, connection.createArrayOf("varchar", REQUIRED_EVIDENCE.toArray()));
			query.setTimestamp(3, java.sql.Timestamp.from(recovery.measurementTime()));
			query.setTimestamp(4, java.sql.Timestamp.from(voc.measurementTime()));
			try (var result = query.executeQuery()) {
				while (result.next()) {
					var numeric = nullableDouble(result, 4);
					if (numeric == null || !Double.isFinite(numeric) || numeric < 0) {
						throw invalid("Invalid numeric evidence " + result.getString(2));
					}
					var sample = result.getObject(6, Long.class);
					var expectedUnit = "CHARGER_OUTPUT_CURRENT".equals(result.getString(2)) ? "A" : "V";
					if (!expectedUnit.equals(result.getString(5))) {
						throw invalid("Unexpected unit for " + result.getString(2));
					}
					if (("CHARGER_OUTPUT_CURRENT".equals(result.getString(2))
							|| "MIDPOINT_VOLTAGE".equals(result.getString(2)))
							&& (sample == null || sample.longValue() != recovery.sampleId())) {
						throw invalid(result.getString(2) + " is not linked to the canonical Recovery Float sample");
					}
					values.put(result.getString(2), new Evidence(result.getLong(1), result.getString(2),
							result.getString(3), numeric, result.getString(5), sample, instant(result, 7)));
				}
			}
		}
		if (!values.keySet().containsAll(REQUIRED_EVIDENCE)) {
			throw invalid("Approved Initial Baseline is missing required monitoring evidence");
		}
		return Map.copyOf(values);
	}

	private static List<CellCutoff> loadCutoffs(Connection connection, EventRow event, CurveVersion curve)
			throws SQLException {
		var values = new ArrayList<CellCutoff>();
		try (var query = connection.prepareStatement("""
			SELECT cell_index,after_sample_id,cutoff_voltage_v,crossing_measurement_time,cell_usable_capacity_ah
			FROM bms.calibration_cell_cutoff_crossing
			WHERE calibration_event_id=? AND calibration_process_id=? AND process_attempt_number=?
			ORDER BY cell_index
			""")) {
			query.setLong(1, event.id());
			query.setLong(2, curve.calibrationProcessId());
			query.setInt(3, curve.processAttemptNumber());
			try (var result = query.executeQuery()) {
				while (result.next()) {
					if (result.getInt(1) != values.size() + 1) throw invalid("Cutoff cell indexes are not contiguous");
					values.add(new CellCutoff(result.getInt(1), result.getLong(2), result.getDouble(3),
							instant(result, 4), result.getDouble(5)));
				}
			}
		}
		return List.copyOf(values);
	}

	private static List<CurvePoint> loadCurvePoints(Connection connection, CurveVersion curve, List<CellCutoff> cutoffs)
			throws SQLException {
		var values = new ArrayList<CurvePoint>();
		try (var query = connection.prepareStatement("""
			SELECT point.cell_index,point.point_order,point.calibration_sample_id,point.milestone_percent,
				point.normalized_actual_dod_percent,point.discharged_ah,point.cell_voltage_v,point.cell_temperature_c,
				point.cell_resistance_uohm,point.cell_soc_pct,point.cell_soh_pct,point.string_voltage_v,
				point.average_voltage_v,point.quality,point.usable
			FROM bms.calibration_curve_point point
			JOIN bms.calibration_sample sample ON sample.id=point.calibration_sample_id
			WHERE point.curve_version_id=? AND sample.calibration_process_id=?
				AND sample.process_attempt_number=?
			ORDER BY point.cell_index,point.point_order
			""")) {
			query.setLong(1, curve.id());
			query.setLong(2, curve.calibrationProcessId());
			query.setInt(3, curve.processAttemptNumber());
			try (var result = query.executeQuery()) {
				while (result.next()) {
					values.add(new CurvePoint(result.getInt(1), result.getInt(2), result.getLong(3), result.getDouble(4),
							result.getDouble(5), result.getDouble(6), result.getDouble(7), nullableDouble(result, 8),
							result.getObject(9, Integer.class), nullableDouble(result, 10), nullableDouble(result, 11),
							nullableDouble(result, 12), nullableDouble(result, 13), result.getString(14), result.getBoolean(15)));
				}
			}
		}
		return List.copyOf(values);
	}

	private static void validateCurve(Connection connection, EventRow event, CurveVersion curve,
			List<CellCutoff> cutoffs, List<CurvePoint> points) throws SQLException {
		if (curve.cellCount() != event.cellCount()) {
			throw invalid("Published curve cell count does not match the approved event");
		}
		if (Math.abs(curve.capacityReferenceAh() - event.capacityReferenceAh()) > 1e-9) {
			throw invalid("Published curve capacity reference does not match the approved event");
		}
		var cutoffSampleByCell = new LinkedHashMap<Integer, Long>();
		var endpoints = new ArrayList<InitialBaselineCurveIntegrity.CellEndpoint>(cutoffs.size());
		for (var cutoff : cutoffs) {
			cutoffSampleByCell.put(cutoff.cellIndex(), cutoff.sampleId());
			endpoints.add(new InitialBaselineCurveIntegrity.CellEndpoint(cutoff.cellIndex(), cutoff.sampleId(),
					cutoff.usableCapacityAh()));
		}
		var integrityPoints = new ArrayList<InitialBaselineCurveIntegrity.Point>(points.size());
		for (var point : points) {
			integrityPoints.add(new InitialBaselineCurveIntegrity.Point(point.cellIndex(), point.pointOrder(),
					point.calibrationSampleId(), point.milestonePercent(), point.normalizedActualDodPercent(),
					point.dischargedAh(), point.cellVoltageV(), point.cellTemperatureC(), point.cellResistanceUohm(),
					point.cellSocPct(), point.cellSohPct(), point.stringVoltageV(), point.averageVoltageV(),
					point.quality(), point.usable(),
					point.calibrationSampleId() == cutoffSampleByCell.getOrDefault(point.cellIndex(), -1L)));
		}
		var rawSourcePoints = CalibrationEventStore.integrityPoints(CalibrationEventStore.prepareCurveSourcePoints(
				CalibrationEventStore.loadCurveSourcePoints(connection, curve.calibrationProcessId(),
						curve.processAttemptNumber(), event.capacityReferenceAh()), event.cellCount()));
		if (integrityPoints.size() != rawSourcePoints.size()) {
			throw invalid("Stored curve does not match its canonical source samples");
		}
		// One-release compatibility: old curves included calculator estimates in the
		// signed payload; new curves intentionally store them as NULL. Project the
		// source to the stored schema before comparison/hash. The content hash still
		// detects any accidental or malicious change to either representation.
		var sourcePoints = alignDeprecatedEstimates(integrityPoints, rawSourcePoints);
		for (int index = 0; index < integrityPoints.size(); index++) {
			if (!sameCurvePoint(integrityPoints.get(index), sourcePoints.get(index))) {
				throw invalid("Stored curve point does not match its canonical source sample");
			}
		}
		InitialBaselineCurveIntegrity.validatePublished(
				new InitialBaselineCurveIntegrity.Header(curve.schemaVersion(), event.id(), event.stringId(),
						curve.calibrationProcessId(), curve.processAttemptNumber(), event.capacityReferenceAh(),
						curve.stringSafeUsableCapacityAh(), curve.allCellsEndpointAh(), curve.cellCount()),
				curve.pointCount(), endpoints, integrityPoints, sourcePoints, curve.contentHash());
	}

	static List<InitialBaselineCurveIntegrity.Point> alignDeprecatedEstimates(
			List<InitialBaselineCurveIntegrity.Point> stored,
			List<InitialBaselineCurveIntegrity.Point> source) {
		if (stored.size() != source.size()) {
			throw invalid("Stored curve does not match its canonical source samples");
		}
		var aligned = new ArrayList<InitialBaselineCurveIntegrity.Point>(source.size());
		for (int index = 0; index < source.size(); index++) {
			var persisted = stored.get(index);
			var measured = source.get(index);
			aligned.add(new InitialBaselineCurveIntegrity.Point(measured.cellIndex(), measured.pointOrder(),
					measured.sampleId(), measured.milestonePercent(), measured.normalizedActualDodPercent(),
					measured.dischargedAh(), measured.cellVoltageV(), measured.cellTemperatureC(),
					measured.cellResistanceUohm(), persisted.cellSocPct() == null ? null : measured.cellSocPct(),
					persisted.cellSohPct() == null ? null : measured.cellSohPct(), measured.stringVoltageV(),
					measured.averageVoltageV(), measured.quality(), measured.usable(), measured.cutoff()));
		}
		return List.copyOf(aligned);
	}

	private static boolean sameCurvePoint(InitialBaselineCurveIntegrity.Point stored,
			InitialBaselineCurveIntegrity.Point source) {
		return stored.cellIndex() == source.cellIndex() && stored.pointOrder() == source.pointOrder()
				&& stored.sampleId() == source.sampleId()
				&& close(stored.milestonePercent(), source.milestonePercent())
				&& close(stored.normalizedActualDodPercent(), source.normalizedActualDodPercent())
				&& close(stored.dischargedAh(), source.dischargedAh())
				&& close(stored.cellVoltageV(), source.cellVoltageV())
				&& equalNullable(stored.cellTemperatureC(), source.cellTemperatureC())
				&& java.util.Objects.equals(stored.cellResistanceUohm(), source.cellResistanceUohm())
				&& equalNullable(stored.cellSocPct(), source.cellSocPct())
				&& equalNullable(stored.cellSohPct(), source.cellSohPct())
				&& equalNullable(stored.stringVoltageV(), source.stringVoltageV())
				&& equalNullable(stored.averageVoltageV(), source.averageVoltageV())
				&& java.util.Objects.equals(stored.quality(), source.quality()) && stored.usable() == source.usable()
				&& stored.cutoff() == source.cutoff();
	}

	private static boolean equalNullable(Double first, Double second) {
		return first == null || second == null ? first == second : close(first, second);
	}

	private static boolean close(double first, double second) {
		return Double.isFinite(first) && Double.isFinite(second) && Math.abs(first - second) <= 1e-9;
	}

	private <T> T read(SqlRead<T> operation) {
		try (var connection = this.database.getConnection()) {
			connection.setReadOnly(true);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			connection.setAutoCommit(false);
			try {
				var result = operation.run(connection);
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Unable to load Initial Baseline reference dataset", e);
		}
	}

	private static int requiredPositiveInt(com.google.gson.JsonObject value, String name) {
		if (!value.has(name) || value.get(name).getAsInt() <= 0) throw invalid("Missing configuration " + name);
		return value.get(name).getAsInt();
	}

	private static double requiredPositive(com.google.gson.JsonObject value, String name) {
		if (!value.has(name)) throw invalid("Missing configuration " + name);
		var number = value.get(name).getAsDouble();
		if (!Double.isFinite(number) || number <= 0) throw invalid("Invalid configuration " + name);
		return number;
	}

	private static Double nullableDouble(ResultSet result, int column) throws SQLException {
		var value = result.getDouble(column);
		return result.wasNull() ? null : value;
	}

	private static Instant instant(ResultSet result, int column) throws SQLException {
		var value = result.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static IllegalStateException invalid(String message) {
		return new IllegalStateException(message);
	}

	@FunctionalInterface
	private interface SqlRead<T> {
		T run(Connection connection) throws SQLException;
	}

	private record EventRow(long id, UUID stringId, String stringKey, int cellCount, double ratedCapacityAh,
			double capacityReferenceAh,
			Instant completedAt, Instant approvedAt, UUID approvedBy) {
	}
}
