package io.openems.edge.bms.service.persistence.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.AlarmDetail;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;
import io.openems.edge.bms.service.alarms.implement.AlarmLifecyclePolicy;
import io.openems.edge.bms.service.alarms.implement.persistence.MonitoringAlarmStore;
import io.openems.edge.bms.service.alarms.implement.persistence.MonitoringAlarmStore.ProcessResult;
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService.AcknowledgeResult;

/** Destructive fixture; enabled only for an explicitly disposable database. */
public class MonitoringAlarmSchemaPostgresTest {

	private PGSimpleDataSource dataSource;
	private boolean disposableValidated;
	private MonitoringAlarmStore store;
	private UUID generation;
	private Instant start;

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
		try (var connection = this.dataSource.getConnection(); var statement = connection.prepareStatement("""
			INSERT INTO bms.bms_string(id,string_key,display_name,cell_count) VALUES (?,'str1','String 1',110)
			""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.executeUpdate();
		}
		this.store = new MonitoringAlarmStore(database);
		this.generation = UUID.randomUUID();
		this.start = Instant.parse("2026-09-07T00:00:00Z");
	}

	@After
	public void tearDown() throws Exception {
		if (this.dataSource == null || !this.disposableValidated) return;
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
	}

	@Test
	public void migrationIsIdempotentAndCreatesAlarmTables() throws Exception {
		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));
		try (var connection = this.dataSource.getConnection()) {
			for (var table : new String[] { "monitoring_alarm_processor_state", "monitoring_alarm_state",
					"monitoring_alarm_occurrence", "monitoring_alarm_transition" }) {
				assertTrue(hasTable(connection, table));
			}
			assertEquals(1, scalarLong(connection, "SELECT count(*) FROM bms.schema_version WHERE version=6"));
		}
	}

	@Test
	public void listCheckpointDistinguishesUnprocessedFromProcessedWithoutAlarmsPerString() throws Exception {
		var before = this.store.listWithCheckpoint("str1", false, 0, 100);
		assertTrue(before.alarms().isEmpty());
		assertEquals(null, before.processorCheckpoint().publicationGeneration());
		assertEquals(null, before.processorCheckpoint().publicationRevision());
		assertEquals(null, before.processorCheckpoint().processedAt());

		var publication = UUID.randomUUID();
		var frame = frame(1, false, OperatingMode.IDLE, 0.0, 90.0, List.of())
				.withPublication(publication, 3);
		assertEquals(ProcessResult.PROCESSED,
				this.store.process(frame, AlarmLifecyclePolicy.defaults(), this.start.plusSeconds(2)));
		for (var includeCleared : List.of(false, true)) {
			var result = this.store.listWithCheckpoint("str1", includeCleared, 0, 100);
			assertTrue(result.alarms().isEmpty());
			assertEquals(publication, result.processorCheckpoint().publicationGeneration());
			assertEquals(Long.valueOf(3), result.processorCheckpoint().publicationRevision());
			assertTrue(result.processorCheckpoint().processedAt() != null);
		}
		var secondStringId = UUID.randomUUID();
		var secondPublication = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection(); var stringInsert = connection.prepareStatement("""
			INSERT INTO bms.bms_string(id,string_key,display_name,cell_count)
			VALUES (?,'str2','String 2',110)
			"""); var checkpointInsert = connection.prepareStatement("""
			INSERT INTO bms.monitoring_alarm_processor_state
			(string_id,last_publication_generation,last_publication_revision) VALUES (?,?,?)
			""")) {
			stringInsert.setObject(1, secondStringId);
			stringInsert.executeUpdate();
			checkpointInsert.setObject(1, secondStringId);
			checkpointInsert.setObject(2, secondPublication);
			checkpointInsert.setLong(3, 12);
			checkpointInsert.executeUpdate();
		}
		var second = this.store.listWithCheckpoint("str2", true, 0, 100);
		assertEquals(secondPublication, second.processorCheckpoint().publicationGeneration());
		assertEquals(Long.valueOf(12), second.processorCheckpoint().publicationRevision());
		assertEquals(publication, this.store.listWithCheckpoint("str1", true, 0, 100)
				.processorCheckpoint().publicationGeneration());
		assertEquals(null, this.store.listWithCheckpoint(null, true, 0, 100)
				.processorCheckpoint().publicationRevision());
	}

	@Test
	public void publicationReevaluationDoesNotCountAsMeasurementAndStaleRaisesSensorWithoutReference() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var publication = UUID.randomUUID();
		var detail = new AlarmDetail(Alarm.CHARGE_OVERCURRENT, null, 45.0, 40.0, "Charge overcurrent");
		var first = frame(1, false, OperatingMode.CHARGING, -45.0, 90.0, List.of(detail));
		for (int revision = 1; revision <= 5; revision++) {
			assertEquals(ProcessResult.PROCESSED,
					this.store.process(first.withPublication(publication, revision), policy, this.start.plusSeconds(revision)));
		}
		assertTrue(this.store.list("str1", true, 0, 200).isEmpty());
		var stale = withoutReference(frame(1, true, OperatingMode.UNKNOWN, null, 90.0, List.of()))
				.withPublication(publication, 6);
		assertEquals(ProcessResult.PROCESSED, this.store.process(stale, policy, this.start.plusSeconds(60)));
		assertEquals(ProcessResult.DUPLICATE, this.store.process(stale, policy, this.start.plusSeconds(61)));
		var occurrences = this.store.list("str1", true, 0, 200);
		assertEquals(1, occurrences.size());
		assertEquals(Alarm.SENSOR_MODULE_FAILURE, occurrences.get(0).alarm());
		assertEquals(null, occurrences.get(0).reference());
		assertEquals(null, occurrences.get(0).transitions().get(0).reference());
	}

	@Test
	public void unavailableReferenceDoesNotClearExistingFloatAlarm() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.FLOAT_CHARGE_OVERCURRENT, null, 1.0, 0.3, "Float current high");
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.FLOAT, -1.0, 90.0, List.of(detail)),
					policy, this.start.plusSeconds(sequence));
		}
		for (int sequence = 4; sequence <= 10; sequence++) {
			this.store.process(withoutReference(frame(sequence, false, OperatingMode.FLOAT, 0.0, 90.0, List.of())),
					policy, this.start.plusSeconds(sequence));
		}
		var occurrence = this.store.list("str1", true, 0, 200).stream()
				.filter(value -> value.alarm() == Alarm.FLOAT_CHARGE_OVERCURRENT).findFirst().orElseThrow();
		assertTrue(occurrence.conditionActive());
		assertEquals(null, occurrence.clearedAt());
	}

	private static BmsMonitoringSnapshot withoutReference(BmsMonitoringSnapshot value) {
		var evaluations = new java.util.EnumMap<Alarm, BmsMonitoringSnapshot.AlarmEvaluation>(Alarm.class);
		evaluations.putAll(value.alarmEvaluations());
		evaluations.put(Alarm.FLOAT_CHARGE_OVERCURRENT, BmsMonitoringSnapshot.AlarmEvaluation.UNAVAILABLE);
		evaluations.put(Alarm.CELL_RESISTANCE, BmsMonitoringSnapshot.AlarmEvaluation.UNAVAILABLE);
		return new BmsMonitoringSnapshot(value.stringKey(), value.snapshotGeneration(), value.snapshotSequence(),
				value.capturedAt(), value.currentMeasuredAt(), value.currentQuality(), value.stale(), null,
				value.string(), value.cells(), value.thresholds(), value.activeCalibration(), value.operatingMode(),
				value.currentDirectionMapping(), value.activeAlarms(), value.alarmMask(), value.alarmDetails(),
				value.qualityWarning(), value.calculationSnapshot(), value.publicationGeneration(),
				value.publicationRevision(), evaluations);
	}

	@Test
	public void debounceIsIdempotentAndPersistsBaselineAndCalibrationIdentity() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.FLOAT_CHARGE_OVERCURRENT, null, 1.0, 0.3,
				"Float current exceeds Initial Baseline multiplier");
		for (int sequence = 1; sequence <= 3; sequence++) {
			assertEquals(ProcessResult.PROCESSED, this.store.process(frame(sequence, false, OperatingMode.FLOAT,
					-1.0, 90.0, List.of(detail)), policy, this.start.plusSeconds(sequence)));
		}
		assertEquals(ProcessResult.DUPLICATE, this.store.process(frame(3, false, OperatingMode.FLOAT,
				-1.0, 90.0, List.of(detail)), policy, this.start.plusSeconds(4)));

		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement();
				var row = statement.executeQuery("""
				SELECT alarm_type,status,trigger_observed_value,trigger_threshold_value,
					reference_event_id,reference_curve_version_id,reference_process_id,
					reference_float_sample_id,reference_voc_sample_id,reference_content_hash,
					active_calibration_event_id,active_calibration_stage_id,active_calibration_process_id,
					active_process_type,policy_version,raise_samples,clear_samples,hysteresis_value,latching
				FROM bms.monitoring_alarm_occurrence
				""")) {
			assertTrue(row.next());
			assertEquals("FLOAT_CHARGE_OVERCURRENT", row.getString(1));
			assertEquals("ACTIVE", row.getString(2));
			assertEquals(1.0, row.getDouble(3), 0.0001);
			assertEquals(0.3, row.getDouble(4), 0.0001);
			assertEquals(42, row.getLong(5));
			assertEquals(2, row.getLong(6));
			assertEquals(100, row.getLong(7));
			assertEquals(200, row.getLong(8));
			assertEquals(201, row.getLong(9));
			assertEquals("hash-42", row.getString(10));
			assertEquals(77, row.getLong(11));
			assertEquals(78, row.getLong(12));
			assertEquals(79, row.getLong(13));
			assertEquals("RECOVERY_FLOAT_STABILIZATION", row.getString(14));
			assertEquals(AlarmLifecyclePolicy.VERSION, row.getInt(15));
			assertEquals(3, row.getInt(16));
			assertEquals(3, row.getInt(17));
			assertEquals(0.05, row.getDouble(18), 0.0001);
			assertFalse(row.getBoolean(19));
		}
	}

	@Test
	public void staleAndUnknownFramesCannotFalselyClearCurrentAlarm() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.CHARGE_OVERCURRENT, null, 45.0, 40.0,
				"Charge current exceeds applied limit");
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.CHARGING, -45.0, 90.0, List.of(detail)),
					policy, this.start.plusSeconds(sequence));
		}
		this.store.process(frame(4, true, OperatingMode.UNKNOWN, null, 90.0,
				List.of(new AlarmDetail(Alarm.SENSOR_MODULE_FAILURE, null, null, null, "stale"))), policy,
				this.start.plusSeconds(4));
		this.store.process(frame(5, false, OperatingMode.UNKNOWN, 0.0, 90.0, List.of()), policy,
				this.start.plusSeconds(5));
		try (var connection = this.dataSource.getConnection()) {
			assertEquals("ACTIVE", scalarString(connection, """
				SELECT lifecycle_status FROM bms.monitoring_alarm_state
				WHERE alarm_type='CHARGE_OVERCURRENT' AND subject_key='STRING'
				"""));
			assertEquals(0, scalarLong(connection, """
				SELECT clear_count FROM bms.monitoring_alarm_state
				WHERE alarm_type='CHARGE_OVERCURRENT' AND subject_key='STRING'
				"""));
		}
	}

	@Test
	public void acknowledgeRemainsCompatibleButRecoveryClearsAutomatically() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.CHARGE_OVERCURRENT, null, 45.0, 40.0, "over current");
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.CHARGING, -45.0, 90.0, List.of(detail)),
					policy, this.start.plusSeconds(sequence));
		}
		long occurrenceId;
		try (var connection = this.dataSource.getConnection()) {
			occurrenceId = scalarLong(connection, "SELECT id FROM bms.monitoring_alarm_occurrence");
		}
		assertEquals(AcknowledgeResult.ACKNOWLEDGED_ACTIVE,
				this.store.acknowledge(occurrenceId, "operator", this.start.plusSeconds(4)));
		try (var connection = this.dataSource.getConnection()) {
			assertEquals("ACTIVE", scalarString(connection,
					"SELECT status FROM bms.monitoring_alarm_occurrence WHERE id=" + occurrenceId));
		}
		for (int sequence = 4; sequence <= 6; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.CHARGING, 0.0, 90.0, List.of()), policy,
					this.start.plusSeconds(sequence + 1));
		}
		try (var connection = this.dataSource.getConnection()) {
			assertEquals("CLEARED", scalarString(connection,
					"SELECT status FROM bms.monitoring_alarm_occurrence WHERE id=" + occurrenceId));
			assertEquals(4, scalarLong(connection,
					"SELECT count(*) FROM bms.monitoring_alarm_transition WHERE occurrence_id=" + occurrenceId));
			assertEquals("INACTIVE", scalarString(connection, """
				SELECT lifecycle_status FROM bms.monitoring_alarm_state
				WHERE alarm_type='CHARGE_OVERCURRENT' AND subject_key='STRING'
				"""));
		}
	}

	@Test
	public void floatCurrentAlarmsClearAutomaticallyAfterRecovery() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var details = List.of(
				new AlarmDetail(Alarm.FLOAT_CHARGE_OVERCURRENT, null, 45.0, 0.3, "float over current"),
				new AlarmDetail(Alarm.CHARGE_OVERCURRENT, null, 45.0, 40.0, "charge over current"));
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.FLOAT, -45.0, 90.0, details), policy,
					this.start.plusSeconds(sequence));
		}
		for (int sequence = 4; sequence <= 6; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.IDLE, 0.0, 90.0, List.of()), policy,
					this.start.plusSeconds(sequence));
		}
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(2, scalarLong(connection,
					"SELECT count(*) FROM bms.monitoring_alarm_occurrence WHERE status='CLEARED'"));
			assertEquals("INACTIVE", scalarString(connection, """
				SELECT lifecycle_status FROM bms.monitoring_alarm_state
				WHERE alarm_type='FLOAT_CHARGE_OVERCURRENT'
				"""));
			assertEquals("INACTIVE", scalarString(connection, """
				SELECT lifecycle_status FROM bms.monitoring_alarm_state
				WHERE alarm_type='CHARGE_OVERCURRENT'
				"""));
			assertEquals(0, scalarLong(connection, """
				SELECT count(*) FROM bms.monitoring_alarm_transition
				WHERE transition_type='ACKNOWLEDGED'
				"""));
		}
	}

	@Test
	public void cellAlarmUsesIndependentCellSubject() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.CELL_RESISTANCE, 7, 2_600.0, 2_250.0,
				"Cell resistance exceeds its Initial Baseline");
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.IDLE, 0.0, 90.0, List.of(detail)), policy,
					this.start.plusSeconds(sequence));
		}
		try (var connection = this.dataSource.getConnection()) {
			assertEquals("CELL:7", scalarString(connection,
					"SELECT subject_key FROM bms.monitoring_alarm_occurrence"));
			assertEquals(7, scalarLong(connection,
					"SELECT cell_index FROM bms.monitoring_alarm_occurrence"));
		}
	}

	@Test
	public void alarmReadbackIncludesReferenceContextPolicyAndTransitionIdentity() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.CELL_RESISTANCE, 7, 2_600.0, 2_250.0,
				"Cell resistance exceeds its Initial Baseline");
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.IDLE, 0.0, 90.0, List.of(detail)), policy,
					this.start.plusSeconds(sequence));
		}

		var values = this.store.list("str1", false, 0, 100);
		assertEquals(1, values.size());
		var occurrence = values.get(0);
		assertEquals("str1", occurrence.stringKey());
		assertEquals(Alarm.CELL_RESISTANCE, occurrence.alarm());
		assertEquals("CELL:7", occurrence.subjectKey());
		assertEquals(42, occurrence.reference().calibrationEventId());
		assertEquals(77L, occurrence.activeCalibration().calibrationEventId().longValue());
		assertEquals(AlarmLifecyclePolicy.VERSION, occurrence.policyVersion());
		assertEquals(this.generation, occurrence.triggerGeneration());
		assertEquals(3, occurrence.triggerSequence());
		assertEquals(1, occurrence.transitions().size());
		assertEquals("RAISED", occurrence.transitions().get(0).type());
		assertEquals(this.generation, occurrence.transitions().get(0).sourceGeneration());
		assertEquals(3, occurrence.transitions().get(0).sourceSequence());
		assertEquals(0, this.store.list("str1", true, 1, 100).size());
	}

	@Test
	public void durableCheckpointRejectsSequenceRollbackAndDegradedFrameRaisesSensorWithoutDetail()
			throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		assertEquals(ProcessResult.PROCESSED, this.store.process(
				frame(10, true, OperatingMode.UNKNOWN, null, 90.0, List.of()), policy,
				this.start.plusSeconds(10)));
		// A new store instance models process restart; the database checkpoint remains
		// authoritative and a lower sequence in the same generation is late.
		var restarted = new MonitoringAlarmStore(new TestDatabase(this.dataSource));
		assertEquals(ProcessResult.LATE, restarted.process(
				frame(9, true, OperatingMode.UNKNOWN, null, 90.0, List.of()), policy,
				this.start.plusSeconds(11)));
		try (var connection = this.dataSource.getConnection()) {
			assertEquals(1, scalarLong(connection, """
				SELECT count(*) FROM bms.monitoring_alarm_occurrence
				WHERE alarm_type='SENSOR_MODULE_FAILURE' AND status='ACTIVE'
				"""));
			assertEquals(10, scalarLong(connection, """
				SELECT last_sequence FROM bms.monitoring_alarm_processor_state
				"""));
		}
	}

	@Test
	public void automaticClearPersistsConditionAndClosureTransitions() throws Exception {
		var policy = AlarmLifecyclePolicy.defaults();
		var detail = new AlarmDetail(Alarm.CHARGE_OVERCURRENT, null, 45.0, 40.0, "over current");
		for (int sequence = 1; sequence <= 3; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.CHARGING, -45.0, 90.0, List.of(detail)),
					policy, this.start.plusSeconds(sequence));
		}
		for (int sequence = 4; sequence <= 5; sequence++) {
			this.store.process(frame(sequence, false, OperatingMode.CHARGING, 0.0, 90.0, List.of()), policy,
					this.start.plusSeconds(sequence));
		}
		long occurrenceId;
		try (var connection = this.dataSource.getConnection()) {
			occurrenceId = scalarLong(connection, "SELECT id FROM bms.monitoring_alarm_occurrence");
		}

		assertEquals(ProcessResult.PROCESSED,
				this.store.process(frame(6, false, OperatingMode.CHARGING, 0.0, 90.0, List.of()), policy,
						this.start.plusSeconds(6)));

		try (var connection = this.dataSource.getConnection()) {
			assertEquals("CLEARED", scalarString(connection,
					"SELECT status FROM bms.monitoring_alarm_occurrence WHERE id=" + occurrenceId));
			assertEquals(0, scalarLong(connection, """
				SELECT count(*) FROM bms.monitoring_alarm_transition
				WHERE occurrence_id=%d AND transition_type='ACKNOWLEDGED'
				""".formatted(occurrenceId)));
			assertEquals(1, scalarLong(connection, """
				SELECT count(*) FROM bms.monitoring_alarm_transition
				WHERE occurrence_id=%d AND transition_type='CONDITION_CLEARED'
				""".formatted(occurrenceId)));
			assertEquals(1, scalarLong(connection, """
				SELECT count(*) FROM bms.monitoring_alarm_transition
				WHERE occurrence_id=%d AND transition_type='CLEARED'
				""".formatted(occurrenceId)));
		}
	}

	private BmsMonitoringSnapshot frame(long sequence, boolean stale, OperatingMode mode, Double current,
			double soc, List<AlarmDetail> details) {
		var at = this.start.plusSeconds(sequence);
		var alarms = java.util.EnumSet.noneOf(Alarm.class);
		for (var detail : details) alarms.add(detail.alarm());
		int mask = alarms.stream().mapToInt(Alarm::mask).reduce(0, (left, right) -> left | right);
		var reference = new BmsMonitoringSnapshot.ReferenceIdentity(42, 2, 100, 0, 200, 201, "hash-42");
		var string = new BmsMonitoringSnapshot.StringMeasurements(253.0, 126.5, current,
				current == null ? null : 253.0 * current,
				mode == OperatingMode.FLOAT && current != null && current < 0 ? -current : null,
				2.2, 2.4, 2.3, 2_600, 1_800.0, 25.0, 27.0, 25.5, soc, null, 88.0, 85.0);
		var cells = Map.of(7, new BmsMonitoringSnapshot.CellMeasurements(7, 2.3, 2_600.0, 25.0, soc, 85.0, null));
		var thresholds = new BmsMonitoringSnapshot.AppliedThresholds(0.15, 0.15, 200, 242, 1.25,
				4, 6, 3, 0.3, 40, 70, 40, 2);
		var context = new ActiveCalibrationContext(77L, 78L, 79L, "INITIAL_BASELINE", "RECOVERY_CHARGE",
				"RECOVERY_FLOAT_STABILIZATION", 40.0);
		var modeEvidence = new ModeEvidence(mode, ModeSource.ACTIVE_CALIBRATION, at, this.generation, sequence,
				mode == OperatingMode.UNKNOWN ? ModeQuality.UNAVAILABLE : ModeQuality.VALID, "fixture");
		return new BmsMonitoringSnapshot("str1", this.generation, sequence, at, at.toEpochMilli(),
				stale ? CurrentQuality.RETAINED : CurrentQuality.FRESH, stale, reference, string, cells, thresholds,
				context, modeEvidence, BmsCurrentDirectionMapping.NEGATIVE, Set.copyOf(alarms), mask, details,
				stale ? "stale" : null);
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

	private static String scalarString(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getString(1);
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
