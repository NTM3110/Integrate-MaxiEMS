package io.openems.edge.bms.service.calculation.implement.checkpoint.persistence;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorReferenceIdentity;
import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

/** JDBC implementation kept behind the async checkpoint service. */
public final class SocEstimatorCheckpointRepository {

	private static final Gson GSON = new Gson();
	private final BmsDatabase database;

	public SocEstimatorCheckpointRepository(BmsDatabase database) {
		this.database = database;
	}

	public Map<String, SocEstimatorCheckpoint> loadAll() throws SQLException {
		var values = new LinkedHashMap<String, SocEstimatorCheckpoint>();
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("""
					SELECT string_key,schema_version,cell_count,source_generation,source_sequence,
						measurement_time,reference_event_id,reference_curve_version_id,reference_process_id,
						reference_process_attempt,reference_content_hash,cell_state
					FROM bms.soc_estimator_checkpoint
					"""); var result = statement.executeQuery()) {
			while (result.next()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> cells = GSON.fromJson(result.getString("cell_state"), Map.class);
				var typedCells = new LinkedHashMap<Integer, SocEstimatorCheckpoint.CellState>();
				for (var entry : cells.entrySet()) {
					var cellIndex = Integer.parseInt(String.valueOf(entry.getKey()));
					typedCells.put(cellIndex,
							GSON.fromJson(GSON.toJsonTree(entry.getValue()), SocEstimatorCheckpoint.CellState.class));
				}
				var reference = new EstimatorReferenceIdentity(result.getLong("reference_event_id"),
						result.getLong("reference_curve_version_id"), result.getLong("reference_process_id"),
						result.getInt("reference_process_attempt"), result.getString("reference_content_hash"));
				var checkpoint = new SocEstimatorCheckpoint(result.getInt("schema_version"),
						result.getString("string_key"), result.getInt("cell_count"),
						result.getObject("source_generation", java.util.UUID.class), result.getLong("source_sequence"),
						result.getTimestamp("measurement_time").toInstant().toEpochMilli(), reference, typedCells);
				values.put(checkpoint.stringKey(), checkpoint);
			}
		}
		return Map.copyOf(values);
	}

	public boolean save(SocEstimatorCheckpoint value) throws SQLException {
		try (var connection = this.database.getConnection(); var statement = connection.prepareStatement("""
				INSERT INTO bms.soc_estimator_checkpoint(string_id,string_key,schema_version,cell_count,
					source_generation,source_sequence,measurement_time,reference_event_id,
					reference_curve_version_id,reference_process_id,reference_process_attempt,
					reference_content_hash,cell_state,updated_at)
				SELECT id,?, ?,?,?,?,?,?,?,?,?,?,?::jsonb,now() FROM bms.bms_string WHERE string_key=?
				ON CONFLICT (string_id) DO UPDATE SET string_key=EXCLUDED.string_key,
					schema_version=EXCLUDED.schema_version,cell_count=EXCLUDED.cell_count,
					source_generation=EXCLUDED.source_generation,source_sequence=EXCLUDED.source_sequence,
					measurement_time=EXCLUDED.measurement_time,reference_event_id=EXCLUDED.reference_event_id,
					reference_curve_version_id=EXCLUDED.reference_curve_version_id,
					reference_process_id=EXCLUDED.reference_process_id,
					reference_process_attempt=EXCLUDED.reference_process_attempt,
					reference_content_hash=EXCLUDED.reference_content_hash,cell_state=EXCLUDED.cell_state,
					updated_at=now()
				WHERE EXCLUDED.measurement_time >= soc_estimator_checkpoint.measurement_time
				""")) {
			var reference = value.reference();
			statement.setString(1, value.stringKey()); statement.setInt(2, value.schemaVersion());
			statement.setInt(3, value.cellCount()); statement.setObject(4, value.sourceGeneration());
			statement.setLong(5, value.sourceSequence());
			statement.setTimestamp(6, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(value.measurementTimestamp())));
			statement.setLong(7, reference.calibrationEventId()); statement.setLong(8, reference.curveVersionId());
			statement.setLong(9, reference.calibrationProcessId()); statement.setInt(10, reference.processAttemptNumber());
			statement.setString(11, reference.contentHash()); statement.setString(12, GSON.toJson(value.cells()));
			statement.setString(13, value.stringKey());
			return statement.executeUpdate() == 1;
		}
	}

	public void delete(String stringKey) throws SQLException {
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("DELETE FROM bms.soc_estimator_checkpoint WHERE string_key=?")) {
			statement.setString(1, stringKey);
			statement.executeUpdate();
		}
	}
}
