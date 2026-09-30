package io.openems.edge.bms.service.persistence.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;
import io.openems.edge.bms.service.history.implement.MonitoringHistoryPolicy;
import io.openems.edge.bms.service.history.implement.persistence.MonitoringHistoryStore;
import io.openems.edge.bms.service.history.implement.persistence.MonitoringHistoryStore.PersistResult;

/** Destructive fixture; enabled only for a disposable PostgreSQL database. */
public class MonitoringHistorySchemaPostgresTest {

	private PGSimpleDataSource dataSource;
	private boolean disposableValidated;
	private UUID stringId;
	private MonitoringHistoryStore store;

	@Before
	public void setUp() throws Exception {
		var url = System.getenv("BMS_TEST_POSTGRES_URL");
		Assume.assumeTrue("BMS_TEST_POSTGRES_URL is not configured", url != null && !url.isBlank());
		this.dataSource = new PGSimpleDataSource();
		this.dataSource.setUrl(url);
		this.dataSource.setUser(environment("BMS_TEST_POSTGRES_USER", "postgres"));
		this.dataSource.setPassword(environment("BMS_TEST_POSTGRES_PASSWORD", "postgres"));
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT current_database()")) {
			result.next();
			Assume.assumeFalse("Refusing to drop the operational maxiems database schema",
					Set.of("maxiems", "bms", "postgres", "template0", "template1")
							.contains(result.getString(1).toLowerCase(java.util.Locale.ROOT)));
			this.disposableValidated = true;
		}
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
		BmsSchema.migrate(this.dataSource);
		var database = new TestDatabase(this.dataSource);
		FeatureSchemaMigration.migrate(database);
		this.stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection(); var statement = connection.prepareStatement("""
			INSERT INTO bms.bms_string(id,string_key,display_name,cell_count) VALUES (?,'str1','String 1',110)
			""")) {
			statement.setObject(1, this.stringId);
			statement.executeUpdate();
		}
		this.store = new MonitoringHistoryStore(database);
	}

	@After
	public void tearDown() throws Exception {
		if (this.dataSource == null || !this.disposableValidated) return;
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
	}

	@Test
	public void migrationIsAdditiveAndIdempotent() throws Exception {
		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));
		try (var connection = this.dataSource.getConnection()) {
			for (var table : new String[] { "monitoring_history_state", "monitoring_raw", "monitoring_5m",
					"monitoring_hourly" }) {
				assertTrue(hasTable(connection, table));
			}
			assertEquals(1, scalarLong(connection, "SELECT count(*) FROM bms.schema_version WHERE version=5"));
		}
	}

	@Test
	public void cadenceReplayLateAndRestartAreDeterministic() throws Exception {
		var policy = MonitoringHistoryPolicy.defaults();
		var generation = UUID.randomUUID();
		var start = Instant.parse("2026-01-01T00:00:00Z");
		assertEquals(PersistResult.PERSISTED, this.store.persist(frame(generation, 1, start, 100), policy, start));
		assertEquals(PersistResult.CADENCE_SKIPPED,
				this.store.persist(frame(generation, 2, start.plusSeconds(15), 110), policy, start.plusSeconds(15)));
		assertEquals(PersistResult.DUPLICATE,
				this.store.persist(frame(generation, 2, start.plusSeconds(15), 110), policy, start.plusSeconds(16)));
		assertEquals(PersistResult.LATE,
				this.store.persist(frame(generation, 3, start.plusSeconds(10), 105), policy, start.plusSeconds(17)));
		assertEquals(PersistResult.PERSISTED,
				this.store.persist(frame(generation, 4, start.plusSeconds(30), 120), policy, start.plusSeconds(30)));
		assertEquals(PersistResult.LATE,
				this.store.persist(frame(generation, 3, start.plusSeconds(45), 125), policy, start.plusSeconds(45)));

		var restarted = new MonitoringHistoryStore(new TestDatabase(this.dataSource));
		assertEquals(PersistResult.DUPLICATE,
				restarted.persist(frame(generation, 4, start.plusSeconds(30), 120), policy, start.plusSeconds(31)));
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(2, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_raw"));
			assertEquals(start.plusSeconds(30), timestamp(connection,
					"SELECT last_persisted_captured_at FROM bms.monitoring_history_state"));
		}
	}

	@Test
	public void noReferenceHistoryPersistsAndPublicationOnlyChangeDoesNotDuplicateMeasurement() throws Exception {
		var policy = MonitoringHistoryPolicy.defaults();
		var at = Instant.parse("2026-01-01T00:00:00Z");
		var value = frame(UUID.randomUUID(), 1, at, 230);
		var missing = new BmsMonitoringSnapshot(value.stringKey(), value.snapshotGeneration(), value.snapshotSequence(),
				value.capturedAt(), value.currentMeasuredAt(), value.currentQuality(), value.stale(), null,
				value.string(), value.cells(), value.thresholds(), value.activeCalibration(), value.operatingMode(),
				value.currentDirectionMapping(), value.activeAlarms(), value.alarmMask(), value.alarmDetails(),
				value.qualityWarning(), value.calculationSnapshot(), UUID.randomUUID(), 1, value.alarmEvaluations());
		assertEquals(PersistResult.PERSISTED, this.store.persist(missing, policy, at));
		assertEquals(PersistResult.DUPLICATE,
				this.store.persist(missing.withPublication(missing.publicationGeneration(), 2), policy, at.plusSeconds(60)));
		this.store.rollupAndRetain(at.plusSeconds(3660), policy);
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(1, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_raw WHERE reference_event_id IS NULL"));
			assertEquals(1, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_5m WHERE reference_event_id IS NULL"));
			assertEquals(1, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_hourly WHERE reference_event_id IS NULL"));
		}
	}

	@Test
	public void persistsAllSeventeenMetricsAndFrameIdentity() throws Exception {
		var policy = MonitoringHistoryPolicy.defaults();
		var generation = UUID.randomUUID();
		var capturedAt = Instant.parse("2026-01-01T00:00:00Z");
		assertEquals(PersistResult.PERSISTED,
				this.store.persist(frame(generation, 7, capturedAt, 253.0), policy, capturedAt.plusSeconds(1)));

		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement();
				var result = statement.executeQuery("""
					SELECT source_generation,source_sequence,captured_at,current_measured_at,current_quality,stale,
						reference_event_id,reference_curve_version_id,reference_process_id,
						reference_process_attempt,reference_float_sample_id,reference_voc_sample_id,
						reference_content_hash,operating_mode,operating_mode_source,operating_mode_quality,
						current_direction_mapping,alarm_mask,
						battery_voltage_v,midpoint_voltage_v,battery_current_a,battery_power_w,float_current_a,
						min_cell_voltage_v,max_cell_voltage_v,average_cell_voltage_v,max_cell_resistance_uohm,
						average_cell_resistance_uohm,ambient_temperature_c,max_cell_temperature_c,
						average_cell_temperature_c,battery_soc_pct,remaining_discharge_hours,battery_soh_pct,
						minimum_cell_soh_pct
					FROM bms.monitoring_raw
					""")) {
			assertTrue(result.next());
			assertEquals(generation, result.getObject("source_generation", UUID.class));
			assertEquals(7, result.getLong("source_sequence"));
			assertEquals(capturedAt, result.getTimestamp("captured_at").toInstant());
			assertEquals(capturedAt, result.getTimestamp("current_measured_at").toInstant());
			assertEquals("FRESH", result.getString("current_quality"));
			assertEquals(false, result.getBoolean("stale"));
			assertEquals(42, result.getLong("reference_event_id"));
			assertEquals(2, result.getLong("reference_curve_version_id"));
			assertEquals(100, result.getLong("reference_process_id"));
			assertEquals(0, result.getInt("reference_process_attempt"));
			assertEquals(200, result.getLong("reference_float_sample_id"));
			assertEquals(201, result.getLong("reference_voc_sample_id"));
			assertEquals("hash-42", result.getString("reference_content_hash"));
			assertEquals("DISCHARGING", result.getString("operating_mode"));
			assertEquals("TELEMETRY_INFERENCE", result.getString("operating_mode_source"));
			assertEquals("VALID", result.getString("operating_mode_quality"));
			assertEquals("NEGATIVE", result.getString("current_direction_mapping"));
			assertEquals(0, result.getInt("alarm_mask"));
			assertEquals(253.0, result.getDouble("battery_voltage_v"), 0.0001);
			assertEquals(126.5, result.getDouble("midpoint_voltage_v"), 0.0001);
			assertEquals(20.0, result.getDouble("battery_current_a"), 0.0001);
			assertEquals(5_060.0, result.getDouble("battery_power_w"), 0.0001);
			result.getDouble("float_current_a");
			assertTrue(result.wasNull());
			assertEquals(1.8, result.getDouble("min_cell_voltage_v"), 0.0001);
			assertEquals(2.3, result.getDouble("max_cell_voltage_v"), 0.0001);
			assertEquals(2.3, result.getDouble("average_cell_voltage_v"), 0.0001);
			assertEquals(1_900, result.getInt("max_cell_resistance_uohm"));
			assertEquals(1_800.0, result.getDouble("average_cell_resistance_uohm"), 0.0001);
			assertEquals(25.0, result.getDouble("ambient_temperature_c"), 0.0001);
			assertEquals(26.0, result.getDouble("max_cell_temperature_c"), 0.0001);
			assertEquals(25.5, result.getDouble("average_cell_temperature_c"), 0.0001);
			assertEquals(90.0, result.getDouble("battery_soc_pct"), 0.0001);
			assertEquals(5.0, result.getDouble("remaining_discharge_hours"), 0.0001);
			assertEquals(88.0, result.getDouble("battery_soh_pct"), 0.0001);
			assertEquals(85.0, result.getDouble("minimum_cell_soh_pct"), 0.0001);
		}
	}

	@Test
	public void createsClosedUtcBucketsWithExactRawAverages() throws Exception {
		var policy = MonitoringHistoryPolicy.defaults();
		var generation = UUID.randomUUID();
		var start = Instant.parse("2026-01-01T00:00:00Z");
		this.store.persist(frame(generation, 1, start, 100), policy, start);
		this.store.persist(frameWithReference(generation, 2, start.plusSeconds(30), 110, 42, 202), policy,
				start.plusSeconds(30));
		this.store.persist(frame(generation, 3, start.plusSeconds(300), 130, 43), policy, start.plusSeconds(300));

		var first = this.store.rollupAndRetain(start.plusSeconds(360), policy);
		assertEquals(1, first.fiveMinuteRows());
		assertEquals(0, first.hourlyRows());
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(2, scalarLong(connection, "SELECT sample_count FROM bms.monitoring_5m"));
			assertEquals(105.0, scalarDouble(connection,
					"SELECT (metrics_avg->>'batteryVoltageV')::double precision FROM bms.monitoring_5m"), 0.0001);
			assertEquals(0, scalarLong(connection,
					"SELECT (metrics_count->>'floatCurrentA')::bigint FROM bms.monitoring_5m"));
			assertTrue(scalarBoolean(connection, "SELECT mixed_reference FROM bms.monitoring_5m"));
			assertEquals(100, scalarLong(connection, "SELECT reference_process_id FROM bms.monitoring_5m"));
			assertEquals(0, scalarLong(connection, "SELECT reference_process_attempt FROM bms.monitoring_5m"));
			assertEquals(200, scalarLong(connection, "SELECT reference_float_sample_id FROM bms.monitoring_5m"));
			assertEquals(202, scalarLong(connection, "SELECT reference_voc_sample_id FROM bms.monitoring_5m"));
		}

		var second = this.store.rollupAndRetain(start.plusSeconds(3_660), policy);
		assertEquals(1, second.fiveMinuteRows());
		assertEquals(1, second.hourlyRows());
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(3, scalarLong(connection, "SELECT sample_count FROM bms.monitoring_hourly"));
			assertEquals(43, scalarLong(connection, "SELECT reference_event_id FROM bms.monitoring_hourly"));
			assertTrue(scalarBoolean(connection, "SELECT mixed_reference FROM bms.monitoring_hourly"));
			assertEquals(113.33333333333333, scalarDouble(connection,
					"SELECT (metrics_avg->>'batteryVoltageV')::double precision FROM bms.monitoring_hourly"),
					0.0001);
		}
	}

	@Test
	public void rollupRunsBeforeTieredRetention() throws Exception {
		var policy = MonitoringHistoryPolicy.defaults();
		var generation = UUID.randomUUID();
		var now = Instant.parse("2026-01-01T00:00:00Z");
		var times = new Instant[] { now.minus(2_000, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS),
				now.minus(40, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS) };
		for (int i = 0; i < times.length; i++) {
			assertEquals(PersistResult.PERSISTED,
					this.store.persist(frame(generation, i + 1, times[i], 100 + i), policy, times[i]));
		}
		var result = this.store.rollupAndRetain(now, policy);
		assertEquals(3, result.rawDeleted());
		assertEquals(2, result.fiveMinuteDeleted());
		assertEquals(1, result.hourlyDeleted());
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(1, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_raw"));
			assertEquals(2, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_5m"));
			assertEquals(3, scalarLong(connection, "SELECT count(*) FROM bms.monitoring_hourly"));
		}
	}

	private static BmsMonitoringSnapshot frame(UUID generation, long sequence, Instant capturedAt,
			double batteryVoltage) {
		return frame(generation, sequence, capturedAt, batteryVoltage, 42);
	}

	private static BmsMonitoringSnapshot frame(UUID generation, long sequence, Instant capturedAt,
			double batteryVoltage, long referenceEventId) {
		return frameWithReference(generation, sequence, capturedAt, batteryVoltage, referenceEventId, 201);
	}

	private static BmsMonitoringSnapshot frameWithReference(UUID generation, long sequence, Instant capturedAt,
			double batteryVoltage, long referenceEventId, long vocSampleId) {
		var reference = new BmsMonitoringSnapshot.ReferenceIdentity(referenceEventId, referenceEventId == 42 ? 2 : 3,
				100, 0, 200, vocSampleId, "hash-" + referenceEventId);
		var string = new BmsMonitoringSnapshot.StringMeasurements(batteryVoltage, batteryVoltage / 2, 20.0,
				batteryVoltage * 20, null, 1.8, 2.3, batteryVoltage / 110, 1_900, 1_800.0, 25.0,
				26.0, 25.5, 90.0, 5.0, 88.0, 85.0);
		var mode = new ModeEvidence(OperatingMode.DISCHARGING, ModeSource.TELEMETRY_INFERENCE, capturedAt,
				generation, sequence, ModeQuality.VALID, "fixture");
		return new BmsMonitoringSnapshot("str1", generation, sequence, capturedAt, capturedAt.toEpochMilli(),
				CurrentQuality.FRESH, false, reference, string, Map.of(), null, ActiveCalibrationContext.none(), mode,
				BmsCurrentDirectionMapping.NEGATIVE, Set.of(), 0, java.util.List.of(), null);
	}

	private static boolean hasTable(Connection connection, String table) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='bms' AND table_name=?)
			""")) {
			statement.setString(1, table);
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getBoolean(1);
			}
		}
	}

	private static long scalarLong(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getLong(1);
		}
	}

	private static double scalarDouble(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getDouble(1);
		}
	}

	private static boolean scalarBoolean(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getBoolean(1);
		}
	}

	private static Instant timestamp(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getTimestamp(1).toInstant();
		}
	}

	private static String environment(String key, String fallback) {
		var value = System.getenv(key);
		return value == null || value.isBlank() ? fallback : value;
	}

	private record TestDatabase(PGSimpleDataSource dataSource) implements BmsDatabase {
		@Override
		public Connection getConnection() throws SQLException {
			return this.dataSource.getConnection();
		}

		@Override
		public boolean isAvailable() {
			return true;
		}
	}
}
