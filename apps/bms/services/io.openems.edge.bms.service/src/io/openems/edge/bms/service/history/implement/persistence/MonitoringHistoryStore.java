package io.openems.edge.bms.service.history.implement.persistence;

import io.openems.edge.bms.service.history.implement.MonitoringHistoryPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Transactional PostgreSQL persistence for atomic monitoring frames. */
public final class MonitoringHistoryStore {

	public enum PersistResult {
		PERSISTED, DUPLICATE, LATE, CADENCE_SKIPPED, STRING_NOT_FOUND
	}

	public record MaintenanceResult(int fiveMinuteRows, int hourlyRows, int rawDeleted,
			int fiveMinuteDeleted, int hourlyDeleted) {
	}

	private static final String[] METRIC_COLUMNS = {
			"battery_voltage_v", "midpoint_voltage_v", "battery_current_a", "battery_power_w",
			"float_current_a", "min_cell_voltage_v", "max_cell_voltage_v", "average_cell_voltage_v",
			"max_cell_resistance_uohm", "average_cell_resistance_uohm", "ambient_temperature_c",
			"max_cell_temperature_c", "average_cell_temperature_c", "battery_soc_pct",
			"remaining_discharge_hours", "battery_soh_pct", "minimum_cell_soh_pct" };
	private static final String[] METRIC_KEYS = {
			"batteryVoltageV", "midpointVoltageV", "batteryCurrentA", "batteryPowerW", "floatCurrentA",
			"minCellVoltageV", "maxCellVoltageV", "averageCellVoltageV", "maxCellResistanceUohm",
			"averageCellResistanceUohm", "ambientTemperatureC", "maxCellTemperatureC",
			"averageCellTemperatureC", "batterySocPct", "remainingDischargeHours", "batterySohPct",
			"minimumCellSohPct" };

	private final BmsDatabase database;

	public MonitoringHistoryStore(BmsDatabase database) {
		this.database = database;
	}

	public PersistResult persist(BmsMonitoringSnapshot frame, MonitoringHistoryPolicy policy, Instant persistedAt)
			throws SQLException {
		try (var connection = this.database.getConnection()) {
			connection.setAutoCommit(false);
			try {
				var stringId = findStringId(connection, frame.stringKey());
				if (stringId == null) {
					connection.rollback();
					return PersistResult.STRING_NOT_FOUND;
				}
				ensureState(connection, stringId);
				var state = lockState(connection, stringId);
				if (rawExists(connection, stringId, frame.snapshotGeneration(), frame.snapshotSequence())) {
					connection.commit();
					return PersistResult.DUPLICATE;
				}
				if (frame.snapshotGeneration().equals(state.lastSeenGeneration())
						&& state.lastSeenSequence() != null
						&& frame.snapshotSequence() == state.lastSeenSequence()) {
					connection.commit();
					return PersistResult.DUPLICATE;
				}
				if (frame.snapshotGeneration().equals(state.lastSeenGeneration())
						&& state.lastSeenSequence() != null
						&& frame.snapshotSequence() < state.lastSeenSequence()) {
					connection.commit();
					return PersistResult.LATE;
				}
				if (state.lastSeenCapturedAt() != null && !frame.capturedAt().isAfter(state.lastSeenCapturedAt())) {
					connection.commit();
					return PersistResult.LATE;
				}
				updateLastSeen(connection, stringId, frame);
				if (state.lastPersistedCapturedAt() != null && frame.capturedAt().isBefore(
						state.lastPersistedCapturedAt().plusSeconds(policy.rawCadenceSeconds()))) {
					connection.commit();
					return PersistResult.CADENCE_SKIPPED;
				}
				insertRaw(connection, stringId, frame, persistedAt);
				updateLastPersisted(connection, stringId, frame.capturedAt());
				connection.commit();
				return PersistResult.PERSISTED;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	public MaintenanceResult rollupAndRetain(Instant now, MonitoringHistoryPolicy policy) throws SQLException {
		try (var connection = this.database.getConnection()) {
			connection.setAutoCommit(false);
			try {
				var fiveMinuteCutoff = MonitoringHistoryPolicy.bucketStart(now, 300);
				var hourlyCutoff = MonitoringHistoryPolicy.bucketStart(now, 3_600);
				int fiveMinuteRows = rollup(connection, "monitoring_5m", 300, fiveMinuteCutoff);
				int hourlyRows = rollup(connection, "monitoring_hourly", 3_600, hourlyCutoff);
				int rawDeleted = deleteBefore(connection, "monitoring_raw", "captured_at",
						now.minus(policy.rawRetentionDays(), ChronoUnit.DAYS));
				int fiveMinuteDeleted = deleteBefore(connection, "monitoring_5m", "bucket_start",
						MonitoringHistoryPolicy.bucketStart(
								now.minus(policy.fiveMinuteRetentionDays(), ChronoUnit.DAYS), 300));
				int hourlyDeleted = deleteBefore(connection, "monitoring_hourly", "bucket_start",
						MonitoringHistoryPolicy.bucketStart(
								now.minus(policy.hourlyRetentionDays(), ChronoUnit.DAYS), 3_600));
				connection.commit();
				return new MaintenanceResult(fiveMinuteRows, hourlyRows, rawDeleted, fiveMinuteDeleted,
						hourlyDeleted);
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	private static UUID findStringId(Connection connection, String stringKey) throws SQLException {
		try (var statement = connection.prepareStatement("SELECT id FROM bms.bms_string WHERE string_key=?")) {
			statement.setString(1, stringKey);
			try (var result = statement.executeQuery()) {
				return result.next() ? result.getObject(1, UUID.class) : null;
			}
		}
	}

	private static void ensureState(Connection connection, UUID stringId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO bms.monitoring_history_state(string_id) VALUES (?) ON CONFLICT DO NOTHING
			""")) {
			statement.setObject(1, stringId);
			statement.executeUpdate();
		}
	}

	private static WriterState lockState(Connection connection, UUID stringId) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT last_seen_generation,last_seen_sequence,last_seen_captured_at,last_persisted_captured_at
			FROM bms.monitoring_history_state WHERE string_id=? FOR UPDATE
			""")) {
			statement.setObject(1, stringId);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new SQLException("Monitoring history state was not created");
				return new WriterState(result.getObject(1, UUID.class), (Long) result.getObject(2),
						toInstant(result, 3), toInstant(result, 4));
			}
		}
	}

	private static boolean rawExists(Connection connection, UUID stringId, UUID generation, long sequence)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT 1 FROM bms.monitoring_raw
			WHERE string_id=? AND source_generation=? AND source_sequence=?
			""")) {
			statement.setObject(1, stringId);
			statement.setObject(2, generation);
			statement.setLong(3, sequence);
			try (var result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private static void updateLastSeen(Connection connection, UUID stringId, BmsMonitoringSnapshot frame)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_history_state
			SET last_seen_generation=?,last_seen_sequence=?,last_seen_captured_at=?,updated_at=now()
			WHERE string_id=?
			""")) {
			statement.setObject(1, frame.snapshotGeneration());
			statement.setLong(2, frame.snapshotSequence());
			statement.setTimestamp(3, Timestamp.from(frame.capturedAt()));
			statement.setObject(4, stringId);
			statement.executeUpdate();
		}
	}

	private static void updateLastPersisted(Connection connection, UUID stringId, Instant capturedAt)
			throws SQLException {
		try (var statement = connection.prepareStatement("""
			UPDATE bms.monitoring_history_state
			SET last_persisted_captured_at=?,updated_at=now() WHERE string_id=?
			""")) {
			statement.setTimestamp(1, Timestamp.from(capturedAt));
			statement.setObject(2, stringId);
			statement.executeUpdate();
		}
	}

	private static void insertRaw(Connection connection, UUID stringId, BmsMonitoringSnapshot frame,
			Instant persistedAt) throws SQLException {
		try (var statement = connection.prepareStatement("""
			INSERT INTO bms.monitoring_raw (
				string_id,source_generation,source_sequence,captured_at,current_measured_at,current_quality,stale,
				reference_event_id,reference_curve_version_id,reference_process_id,reference_process_attempt,
				reference_float_sample_id,reference_voc_sample_id,reference_content_hash,
				operating_mode,operating_mode_source,operating_mode_quality,current_direction_mapping,
				alarm_mask,quality_warning,battery_voltage_v,midpoint_voltage_v,battery_current_a,battery_power_w,
				float_current_a,min_cell_voltage_v,max_cell_voltage_v,average_cell_voltage_v,
				max_cell_resistance_uohm,average_cell_resistance_uohm,ambient_temperature_c,
				max_cell_temperature_c,average_cell_temperature_c,battery_soc_pct,remaining_discharge_hours,
				battery_soh_pct,minimum_cell_soh_pct,persisted_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			""")) {
			int index = 1;
			statement.setObject(index++, stringId);
			statement.setObject(index++, frame.snapshotGeneration());
			statement.setLong(index++, frame.snapshotSequence());
			statement.setTimestamp(index++, Timestamp.from(frame.capturedAt()));
			if (frame.currentMeasuredAt() == null) statement.setNull(index++, Types.TIMESTAMP_WITH_TIMEZONE);
			else statement.setTimestamp(index++, Timestamp.from(Instant.ofEpochMilli(frame.currentMeasuredAt())));
			statement.setString(index++, frame.currentQuality().name());
			statement.setBoolean(index++, frame.stale());
			var reference = frame.reference();
			if (reference == null) {
				for (int offset = 0; offset < 7; offset++) statement.setNull(index++, offset == 6 ? Types.VARCHAR
						: offset == 3 ? Types.INTEGER : Types.BIGINT);
			} else {
			statement.setLong(index++, reference.calibrationEventId());
			statement.setLong(index++, reference.curveVersionId());
			statement.setLong(index++, reference.calibrationProcessId());
			statement.setInt(index++, reference.processAttemptNumber());
			statement.setLong(index++, reference.recoveryFloatSampleId());
			statement.setLong(index++, reference.vocSampleId());
			statement.setString(index++, reference.contentHash());
			}
			statement.setString(index++, frame.operatingMode().mode().name());
			statement.setString(index++, frame.operatingMode().source().name());
			statement.setString(index++, frame.operatingMode().quality().name());
			statement.setString(index++, frame.currentDirectionMapping().name());
			statement.setInt(index++, frame.alarmMask());
			statement.setString(index++, frame.qualityWarning());
			var value = frame.string();
			setDouble(statement, index++, value.batteryVoltageV());
			setDouble(statement, index++, value.midpointVoltageV());
			setDouble(statement, index++, value.batteryCurrentA());
			setDouble(statement, index++, value.batteryPowerW());
			setDouble(statement, index++, value.floatCurrentA());
			setDouble(statement, index++, value.minCellVoltageV());
			setDouble(statement, index++, value.maxCellVoltageV());
			setDouble(statement, index++, value.averageCellVoltageV());
			if (value.maxCellResistanceUohm() == null) statement.setNull(index++, Types.INTEGER);
			else statement.setInt(index++, value.maxCellResistanceUohm());
			setDouble(statement, index++, value.averageCellResistanceUohm());
			setDouble(statement, index++, value.ambientTemperatureC());
			setDouble(statement, index++, value.maxCellTemperatureC());
			setDouble(statement, index++, value.averageCellTemperatureC());
			setDouble(statement, index++, value.batterySocPct());
			setDouble(statement, index++, value.remainingDischargeHours());
			setDouble(statement, index++, value.batterySohPct());
			setDouble(statement, index++, value.minimumCellSohPct());
			statement.setTimestamp(index, Timestamp.from(persistedAt));
			statement.executeUpdate();
		}
	}

	private static int rollup(Connection connection, String table, long bucketSeconds, Instant cutoff)
			throws SQLException {
		if (!table.equals("monitoring_5m") && !table.equals("monitoring_hourly")) {
			throw new IllegalArgumentException("Unsupported monitoring aggregate table");
		}
		var bucket = "to_timestamp(floor(extract(epoch from r.captured_at)/" + bucketSeconds + ")*"
				+ bucketSeconds + ")";
		var sql = """
			INSERT INTO bms.%s (
				string_id,bucket_start,bucket_end,sample_count,first_captured_at,last_captured_at,
				reference_event_id,reference_curve_version_id,reference_process_id,reference_process_attempt,
				reference_float_sample_id,reference_voc_sample_id,reference_content_hash,mixed_reference,
				last_operating_mode,last_operating_mode_quality,alarm_mask_or,stale_sample_count,
				metrics_min,metrics_max,metrics_avg,metrics_count)
			SELECT r.string_id,%s,%s + (? * interval '1 second'),count(*),min(r.captured_at),max(r.captured_at),
				(array_agg(r.reference_event_id ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.reference_curve_version_id ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.reference_process_id ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.reference_process_attempt ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.reference_float_sample_id ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.reference_voc_sample_id ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.reference_content_hash ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				count(DISTINCT ROW(r.reference_event_id,r.reference_curve_version_id,r.reference_process_id,
					r.reference_process_attempt,r.reference_float_sample_id,r.reference_voc_sample_id,
					r.reference_content_hash)) > 1,
				(array_agg(r.operating_mode ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				(array_agg(r.operating_mode_quality ORDER BY r.captured_at DESC,r.source_sequence DESC))[1],
				bit_or(r.alarm_mask),count(*) FILTER (WHERE r.stale),
				%s,%s,%s,%s
			FROM bms.monitoring_raw r
			WHERE r.captured_at < ?
				AND NOT EXISTS (SELECT 1 FROM bms.%s existing
					WHERE existing.string_id=r.string_id AND existing.bucket_start=%s)
			GROUP BY r.string_id,%s
			ON CONFLICT DO NOTHING
			""".formatted(table, bucket, bucket, metricsJson("min"), metricsJson("max"), metricsJson("avg"),
					metricsJson("count"), table, bucket, bucket);
		try (var statement = connection.prepareStatement(sql)) {
			statement.setLong(1, bucketSeconds);
			statement.setTimestamp(2, Timestamp.from(cutoff));
			return statement.executeUpdate();
		}
	}

	private static String metricsJson(String aggregate) {
		var result = new StringBuilder("jsonb_strip_nulls(jsonb_build_object(");
		for (int i = 0; i < METRIC_COLUMNS.length; i++) {
			if (i > 0) result.append(',');
			result.append('\'').append(METRIC_KEYS[i]).append("',")
					.append(aggregate).append("(r.").append(METRIC_COLUMNS[i]).append(')');
		}
		return result.append("))").toString();
	}

	private static int deleteBefore(Connection connection, String table, String column, Instant cutoff)
			throws SQLException {
		if (!(table.equals("monitoring_raw") || table.equals("monitoring_5m")
				|| table.equals("monitoring_hourly"))) {
			throw new IllegalArgumentException("Unsupported monitoring retention table");
		}
		try (var statement = connection.prepareStatement(
				"DELETE FROM bms." + table + " WHERE " + column + " < ?")) {
			statement.setTimestamp(1, Timestamp.from(cutoff));
			return statement.executeUpdate();
		}
	}

	private static Instant toInstant(ResultSet result, int index) throws SQLException {
		var value = result.getTimestamp(index);
		return value == null ? null : value.toInstant();
	}

	private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
		if (value == null || !Double.isFinite(value)) statement.setNull(index, Types.DOUBLE);
		else statement.setDouble(index, value);
	}

	private record WriterState(UUID lastSeenGeneration, Long lastSeenSequence, Instant lastSeenCapturedAt,
			Instant lastPersistedCapturedAt) {
	}
}
