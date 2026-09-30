package io.openems.edge.bms.service.persistence.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.openems.edge.bms.service.strings.contracts.BmsStringManager;
import io.openems.edge.bms.service.auth.model.AuthenticatedPrincipal;
import io.openems.edge.bms.service.auth.model.PlatformRole;
import io.openems.edge.bms.service.calibration.implement.CalibrationConflictException;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeMethod;
import io.openems.edge.bms.service.calibration.model.CalibrationBlocker;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionCode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ActionIntent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.AllowedAction;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CreateCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDemoTimeScaleCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureActiveFloatCurrentRangeCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeMonitorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfigureDischargeSafetyFloorCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ConfirmDischargeStoppedCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.DeleteDraftCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ExecuteActionCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ManualEvidenceCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.RedoStageCommand;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.SampleWrite;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.WorkerEvent;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StageState;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.EventStatus;
import io.openems.edge.bms.service.calibration.model.CalibrationEventModels.ProcessState;
import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationEventStore;
import io.openems.edge.bms.service.calibration.implement.CalibrationEventService;
import io.openems.edge.bms.service.calibration.implement.InitialBaselineReferenceDatasetService;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;
import io.openems.edge.bms.service.calibration.model.CalibrationMeasurement;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.PersistenceReason;
import io.openems.edge.bms.service.calibration.model.CalibrationStageEvaluation.Recommendation;
import io.openems.edge.bms.service.calibration.model.CalibrationStageType;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import io.openems.edge.bms.service.strings.implement.persistence.repository.PostgresStringRepository;
import io.openems.edge.bms.service.strings.implement.persistence.StringManagementService;

/** Exercises calibration DDL against PostgreSQL rather than an incompatible SQL substitute. */
public class CalibrationSchemaPostgresTest {
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private PGSimpleDataSource dataSource;
	private boolean disposableValidated;

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
			Assume.assumeFalse("Refusing to drop an operational or system database schema",
					java.util.Set.of("maxiems", "bms", "postgres", "template0", "template1")
							.contains(result.getString(1).toLowerCase(java.util.Locale.ROOT)));
			this.disposableValidated = true;
		}
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
		BmsSchema.migrate(this.dataSource);
		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));
	}

	@After
	public void tearDown() throws Exception {
		if (this.dataSource == null || !this.disposableValidated) return;
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
	}

	@Test
	public void migrationIsIdempotentAndEnforcesAggregateInvariants() throws Exception {
		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			var firstEvent = insertDraft(connection, actorId, stringId);
			assertThrows(SQLException.class, () -> insertDraft(connection, actorId, stringId));

			var stageId = insertStage(connection, firstEvent);
			assertThrows(SQLException.class, () -> insertWrongEventSample(connection, actorId, stageId));

			var generation = UUID.randomUUID();
			insertSample(connection, firstEvent, stageId, generation, 1);
			assertThrows(SQLException.class, () -> insertSample(connection, firstEvent, stageId, generation, 1));
			assertTrue(hasColumn(connection, "calibration_sample", "representative_discharge_temperature_c"));
			assertTrue(hasColumn(connection, "calibration_sample_reason", "reason_key"));
			assertTrue(hasColumn(connection, "calibration_sample_reason", "milestone_value"));
			assertTrue(hasColumn(connection, "calibration_process_runtime", "checkpoint_data"));
			assertTrue(hasColumn(connection, "calibration_process_runtime", "latest_measurement"));
			assertTrue(hasColumn(connection, "calibration_process_transition", "evidence"));
			assertTrue(hasColumn(connection, "calibration_process_runtime", "last_periodic_persisted_at"));
			assertTrue(hasColumn(connection, "calibration_process_runtime", "blockers"));
			assertTrue(hasColumn(connection, "calibration_process_runtime", "recommendation"));
			assertFalse(hasColumn(connection, "calibration_process_runtime", "raw_used_ah"));
			assertTrue(hasColumn(connection, "calibration_discharge_runtime", "raw_used_ah"));
			assertTrue(hasColumn(connection, "calibration_discharge_runtime", "per_cell_state"));
			assertTrue(hasColumn(connection, "calibration_discharge_runtime", "crossed_cell_count"));
			assertTrue(hasColumn(connection, "calibration_discharge_runtime", "configured_cell_count"));
			assertTrue(hasColumn(connection, "calibration_discharge_runtime", "string_safe_usable_capacity_ah"));
			assertTrue(hasColumn(connection, "calibration_discharge_runtime", "all_cells_endpoint_ah"));
			assertTrue(hasColumn(connection, "calibration_cell_cutoff_crossing", "cell_usable_capacity_ah"));
			assertTrue(hasColumn(connection, "calibration_curve_version", "string_safe_usable_capacity_ah"));
			assertTrue(hasColumn(connection, "calibration_curve_point", "normalized_actual_dod_percent"));
			assertTrue(hasColumn(connection, "calibration_active_baseline", "curve_version_id"));
			assertTrue(hasColumn(connection, "calibration_event", "created_by_name"));
			assertTrue(hasColumn(connection, "calibration_event", "baseline_approved_at"));
			assertTrue(hasColumn(connection, "calibration_event", "baseline_approved_by"));
			assertTrue(hasColumn(connection, "calibration_transition", "actor_name"));
			assertTrue(hasColumn(connection, "calibration_manual_evidence", "actor_name"));
			assertTrue(!hasColumn(connection, "calibration_event", "supervisor"));
		}
	}

	@Test
	public void processRuntimeSchemaSeparatesOneDischargeExtensionPerAttempt() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			long eventId = insertDraft(connection, actorId, stringId);
			long stageId = insertStage(connection, eventId);
			long processId = insertProcess(connection, eventId, stageId, "CONTROLLED_DISCHARGE", "{}");
			try (var common = connection.prepareStatement("""
				INSERT INTO bms.calibration_process_runtime
					(calibration_process_id,attempt_number,checkpoint_data,latest_measurement)
				VALUES (?,0,'{"marker":"common"}'::jsonb,'{"cells":{}}'::jsonb)
				"""); var discharge = connection.prepareStatement("""
				INSERT INTO bms.calibration_discharge_runtime
					(calibration_process_id,attempt_number,raw_used_ah,official_used_ah)
				VALUES (?,0,12.5,12.5)
				""")) {
				common.setLong(1, processId);
				common.executeUpdate();
				discharge.setLong(1, processId);
				discharge.executeUpdate();
				assertThrows(SQLException.class, discharge::executeUpdate);
			}
			try (var query = connection.prepareStatement("""
				SELECT runtime.checkpoint_data->>'marker',discharge.raw_used_ah
				FROM bms.calibration_process_runtime runtime
				JOIN bms.calibration_discharge_runtime discharge
					USING (calibration_process_id,attempt_number)
				WHERE runtime.calibration_process_id=? AND runtime.attempt_number=0
				""")) {
				query.setLong(1, processId);
				try (var result = query.executeQuery()) {
					assertTrue(result.next());
					assertEquals("common", result.getString(1));
					assertEquals(12.5, result.getDouble(2), 0.0);
				}
			}
		}
	}

	@Test
	public void migrationBackfillsLegacyRuntimeAndStripsMutableProcessProperties() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long processId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			long eventId = insertDraft(connection, actorId, stringId);
			long stageId = insertStage(connection, eventId);
			processId = insertProcess(connection, eventId, stageId, "BOOST_CHARGE_TO_VBOOST", """
				{"boostTargetVoltageV":4.9,"checkpoint":{"marker":"legacy"},
				 "latestMeasurement":{"stringVoltageV":4.8},"blockers":[],
				 "recommendation":"CONTINUE"}
				""");
		}

		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));

		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT runtime.checkpoint_data->>'marker',
				(runtime.latest_measurement->>'stringVoltageV')::numeric,runtime.recommendation,
				process.properties->>'boostTargetVoltageV',
				jsonb_exists_any(process.properties,
					ARRAY['checkpoint','latestMeasurement','blockers','recommendation']::text[])
			FROM bms.calibration_process process
			JOIN bms.calibration_process_runtime runtime
				ON runtime.calibration_process_id=process.id
				AND runtime.attempt_number=process.attempt_number
			WHERE process.id=?
			""")) {
			query.setLong(1, processId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals("legacy", result.getString(1));
				assertEquals(4.8, result.getDouble(2), 0.0);
				assertEquals("CONTINUE", result.getString(3));
				assertEquals("4.9", result.getString(4));
				assertFalse(result.getBoolean(5));
			}
		}
	}

	@Test
	public void migrationMovesLegacyDischargeStateIntoOneToOneExtension() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long processId;
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			seed(connection, actorId, stringId);
			long eventId = insertDraft(connection, actorId, stringId);
			long stageId = insertStage(connection, eventId);
			processId = insertProcess(connection, eventId, stageId, "CONTROLLED_DISCHARGE", "{}");
			statement.execute("ALTER TABLE bms.calibration_process_runtime ADD COLUMN raw_used_ah NUMERIC NOT NULL DEFAULT 0");
			statement.execute("ALTER TABLE bms.calibration_process_runtime ADD COLUMN official_used_ah NUMERIC NOT NULL DEFAULT 0");
			statement.execute("ALTER TABLE bms.calibration_process_runtime ADD COLUMN next_dod_milestone_percent INTEGER NOT NULL DEFAULT 5");
			try (var insert = connection.prepareStatement("""
				INSERT INTO bms.calibration_process_runtime
					(calibration_process_id,attempt_number,raw_used_ah,official_used_ah,next_dod_milestone_percent)
				VALUES (?,0,18.75,18.5,15)
				""")) {
				insert.setLong(1, processId);
				insert.executeUpdate();
			}
			// Simulate a partially split deployment. The still-present mixed columns are
			// authoritative and must replace, not lose, this older extension state.
			try (var insert = connection.prepareStatement("""
				INSERT INTO bms.calibration_discharge_runtime
					(calibration_process_id,attempt_number,raw_used_ah,official_used_ah)
				VALUES (?,0,1.0,1.0)
				""")) {
				insert.setLong(1, processId);
				insert.executeUpdate();
			}
		}

		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));
		// Re-running on the already-split schema must be a no-op for extension data.
		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));

		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT raw_used_ah,official_used_ah,next_dod_milestone_percent
			FROM bms.calibration_discharge_runtime
			WHERE calibration_process_id=? AND attempt_number=0
			""")) {
			assertFalse(hasColumn(connection, "calibration_process_runtime", "raw_used_ah"));
			query.setLong(1, processId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(18.75, result.getDouble(1), 0.0);
				assertEquals(18.5, result.getDouble(2), 0.0);
				assertEquals(15, result.getInt(3));
			}
		}
	}

	@Test
	public void migrationUpgradesOlderCalibrationTablesForAllAddedStoreReadColumns() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long existingEventId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			existingEventId = store().create(
					new CreateCommand(stringId, monthlyConfiguration(), null, operator(actorId))).event().id();
			var stageId = insertStage(connection, existingEventId);
			var generation = UUID.randomUUID();
			insertSample(connection, existingEventId, stageId, generation, 1);
			try (var transition = connection.prepareStatement("""
				INSERT INTO bms.calibration_transition
					(calibration_event_id,calibration_stage_id,to_state,action,actor_id,actor_name,
					 resulting_event_version)
				VALUES (?,?,'NOT_STARTED','TEST',?,'Operator Name',0)
				"""); var cell = connection.prepareStatement("""
				INSERT INTO bms.calibration_cell_sample(calibration_sample_id,cell_index,quality)
				SELECT id,1,'VALID' FROM bms.calibration_sample
				WHERE calibration_event_id=? AND snapshot_generation=? AND snapshot_sequence=1
				"""); var evidence = connection.prepareStatement("""
				INSERT INTO bms.calibration_manual_evidence
					(calibration_event_id,calibration_stage_id,evidence_type,status,actor_id,actor_name)
				VALUES (?,?,'LEGACY_NOTE','RECORDED',?,'Operator Name')
				""")) {
				transition.setLong(1, existingEventId);
				transition.setLong(2, stageId);
				transition.setObject(3, actorId);
				transition.executeUpdate();
				cell.setLong(1, existingEventId);
				cell.setObject(2, generation);
				cell.executeUpdate();
				evidence.setLong(1, existingEventId);
				evidence.setLong(2, stageId);
				evidence.setObject(3, actorId);
				evidence.executeUpdate();
			}
		}
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE bms.calibration_event DROP CONSTRAINT IF EXISTS calibration_baseline_approval_check");
			dropColumns(statement, "calibration_event", "created_by_name", "measurement_revision",
					"record_schema_version", "restart_revalidation_required", "blockers", "missing_evidence",
					"final_result", "baseline_approved_at", "baseline_approved_by", "baseline_approved_by_name");
			dropColumns(statement, "calibration_stage", "property_schema_version", "properties", "result",
					"blocker_code", "blocker_detail", "started_by", "completed_by", "updated_at");
			dropColumns(statement, "calibration_transition", "reason", "actor_id", "actor_name", "evidence",
					"occurred_at");
			dropColumns(statement, "calibration_sample", "blocker_reason", "raw_current_a", "domain_current_a",
					"current_direction", "mapping_version", "current_measured_at", "current_quality",
					"string_voltage_v", "average_voltage_v", "ambient_temperature_c",
					"average_cell_temperature_c", "delta_seconds", "delta_ah", "raw_used_ah",
					"representative_discharge_temperature_c", "temperature_factor_kc", "corrected_used_ah",
					"soc_pct", "soc_method", "dod_pct", "float_target_voltage_v", "boost_target_voltage_v",
					"created_at");
			dropColumns(statement, "calibration_cell_sample", "voltage_v", "temperature_c", "resistance_uohm",
					"soc_pct", "soc_method", "soh_pct", "soh_method", "availability_warning");
			statement.execute("DROP INDEX IF EXISTS bms.calibration_manual_evidence_event_stage");
			dropColumns(statement, "calibration_manual_evidence", "numeric_value", "text_value", "unit",
					"instrument_id", "instrument_resolution", "instrument_accuracy", "calibration_reference",
					"actor_name", "note", "external_reference", "recorded_at");
		}

		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));

		try (var connection = this.dataSource.getConnection()) {
			assertColumns(connection, "calibration_event", "created_by_name", "measurement_revision",
					"record_schema_version", "restart_revalidation_required", "blockers", "missing_evidence",
					"final_result", "baseline_approved_at", "baseline_approved_by", "baseline_approved_by_name");
			assertColumns(connection, "calibration_stage", "property_schema_version", "properties", "result",
					"blocker_code", "blocker_detail", "started_at", "completed_at", "started_by", "completed_by",
					"updated_at");
			assertColumns(connection, "calibration_transition", "reason", "actor_id", "actor_name", "evidence",
					"resulting_event_version", "transition_order", "occurred_at");
			assertColumns(connection, "calibration_sample", "blocker_reason", "raw_current_a", "domain_current_a",
					"current_direction", "mapping_version", "current_measured_at", "current_quality",
					"string_voltage_v", "average_voltage_v", "ambient_temperature_c",
					"average_cell_temperature_c", "delta_seconds", "delta_ah", "raw_used_ah",
					"representative_discharge_temperature_c", "temperature_factor_kc", "corrected_used_ah",
					"soc_pct", "soc_method", "dod_pct", "float_target_voltage_v", "boost_target_voltage_v",
					"created_at");
			assertColumns(connection, "calibration_cell_sample", "voltage_v", "temperature_c",
					"resistance_uohm", "soc_pct", "soc_method", "soh_pct", "soh_method",
					"availability_warning");
			assertColumns(connection, "calibration_manual_evidence", "numeric_value", "text_value", "unit",
					"instrument_id", "instrument_resolution", "instrument_accuracy", "calibration_reference",
					"actor_name", "note", "external_reference", "recorded_at");

			var store = store();
			var migrated = store.get(existingEventId);
			assertEquals("Operator Name", migrated.event().createdByName());
			assertEquals(1, migrated.stages().size());
			var samples = store.samples(existingEventId);
			assertEquals(1, samples.size());
			assertEquals(1, samples.get(0).cells().size());
			var history = store.history(existingEventId);
			assertEquals(1, history.transitions().size());
			assertEquals(1, history.evidence().size());
			assertEquals("Operator Name", history.evidence().get(0).actorName());
		}
	}

	@Test
	public void existingAuthUsersReceiveRequiredUsernameNameBackfill() throws Exception {
		var actorId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE bms.auth_user DROP COLUMN display_name");
			try (var insert = connection.prepareStatement("""
				INSERT INTO bms.auth_user(id,username,password_hash,salt) VALUES (?,?,'x'::bytea,'y'::bytea)
				""")) {
				insert.setObject(1, actorId);
				insert.setString(2, "legacy-operator");
				insert.executeUpdate();
			}
		}
		BmsSchema.migrate(this.dataSource);
		try (var connection = this.dataSource.getConnection();
				var query = connection.prepareStatement("SELECT display_name FROM bms.auth_user WHERE id=?")) {
			query.setObject(1, actorId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals("legacy-operator", result.getString(1));
			}
		}
	}

	@Test
	public void migratesLegacyCalibrationActorColumnsAndRemovesSupervisor() throws Exception {
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE bms.calibration_event RENAME COLUMN created_by_name TO created_by_username");
			statement.execute("ALTER TABLE bms.calibration_event ADD COLUMN supervisor VARCHAR(160)");
			statement.execute("ALTER TABLE bms.calibration_transition RENAME COLUMN actor_name TO actor_username");
			statement.execute("ALTER TABLE bms.calibration_manual_evidence RENAME COLUMN actor_name TO actor_username");
		}
		FeatureSchemaMigration.migrate(new TestDatabase(this.dataSource));
		try (var connection = this.dataSource.getConnection()) {
			assertTrue(hasColumn(connection, "calibration_event", "created_by_name"));
			assertTrue(hasColumn(connection, "calibration_transition", "actor_name"));
			assertTrue(hasColumn(connection, "calibration_manual_evidence", "actor_name"));
			assertTrue(!hasColumn(connection, "calibration_event", "supervisor"));
			assertTrue(!hasColumn(connection, "calibration_event", "created_by_username"));
			assertTrue(!hasColumn(connection, "calibration_transition", "actor_username"));
			assertTrue(!hasColumn(connection, "calibration_manual_evidence", "actor_username"));
		}
	}

	@Test
	public void appendOnlyAndClosedEventTriggersRejectMutation() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			var eventId = insertDraft(connection, actorId, stringId);
			var stageId = insertStage(connection, eventId);
			try (var transition = connection.prepareStatement("""
				INSERT INTO bms.calibration_transition
					(calibration_event_id,calibration_stage_id,to_state,action,resulting_event_version)
				VALUES (?,?,'ACTIVE','TEST',1) RETURNING id
				""")) {
				transition.setLong(1, eventId);
				transition.setLong(2, stageId);
				try (var result = transition.executeQuery()) {
					assertTrue(result.next());
					var transitionId = result.getLong(1);
					assertThrows(SQLException.class, () -> updateTransition(connection, transitionId));
				}
			}
			try (var close = connection.prepareStatement("""
				UPDATE bms.calibration_event SET status='CANCELLED',closed_at=now() WHERE id=?
				""")) {
				close.setLong(1, eventId);
				assertEquals(1, close.executeUpdate());
			}
			assertThrows(SQLException.class, () -> updateClosedEvent(connection, eventId));
			assertThrows(SQLException.class, () -> deleteClosedEvent(connection, eventId));
		}
	}

	@Test
	public void operatorActionReloadsAtomicallyRejectsStaleRaceAndSnapshotsTelemetry() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long eventId;
		long currentStageId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			eventId = insertDraft(connection, actorId, stringId);
			currentStageId = insertStage(connection, eventId);
			try (var next = connection.prepareStatement("""
				INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type)
				VALUES (?,1,'MONTHLY_INSPECTION')
				"""); var activateStage = connection.prepareStatement("""
				UPDATE bms.calibration_stage SET state='ACTIVE',started_at=now() WHERE id=?
				"""); var activateEvent = connection.prepareStatement("""
				UPDATE bms.calibration_event SET status='ACTIVE',current_stage_id=?,started_at=now() WHERE id=?
				""")) {
				next.setLong(1, eventId);
				next.executeUpdate();
				activateStage.setLong(1, currentStageId);
				activateStage.executeUpdate();
				activateEvent.setLong(1, currentStageId);
				activateEvent.setLong(2, eventId);
				activateEvent.executeUpdate();
			}
			var generation = UUID.randomUUID();
			insertSample(connection, eventId, currentStageId, generation, 9);
			try (var cell = connection.prepareStatement("""
				INSERT INTO bms.calibration_cell_sample(calibration_sample_id,cell_index,voltage_v,temperature_c,quality)
				SELECT id,1,2.31,25.0,'VALID' FROM bms.calibration_sample
				WHERE calibration_event_id=? AND snapshot_generation=? AND snapshot_sequence=9
				""")) {
				cell.setLong(1, eventId);
				cell.setObject(2, generation);
				cell.executeUpdate();
			}
		}

		var store = new CalibrationEventStore();
		var databaseField = CalibrationEventStore.class.getDeclaredField("database");
		databaseField.setAccessible(true);
		databaseField.set(store, new TestDatabase(this.dataSource));
		var actor = new AuthenticatedPrincipal(actorId, "operator", "Operator Name",
				Set.of(PlatformRole.BMS_OPERATOR));
		var command = new ExecuteActionCommand(eventId, currentStageId, ActionCode.COMPLETE_STAGE, 0, null,
				List.of());
		var completed = store.executeAction(command, actor, ignored -> List.of(new AllowedAction(
				ActionCode.COMPLETE_STAGE, "Complete stage", ActionIntent.PRIMARY, true, null, false, false,
				List.of())));
		assertEquals(1, completed.event().eventVersion());
		assertEquals("MONTHLY_INSPECTION", completed.stages().get(1).type().name());
		assertThrows(CalibrationConflictException.class, () -> store.executeAction(command, actor,
				ignored -> List.of(new AllowedAction(ActionCode.COMPLETE_STAGE, "Complete stage", ActionIntent.PRIMARY,
						true, null, false, false, List.of()))));
		var transitionEvidence = store.history(eventId).transitions().get(0).evidence().getAsJsonObject();
		assertEquals(9, transitionEvidence.getAsJsonObject("telemetry").get("snapshot_sequence").getAsLong());
		assertEquals(1, transitionEvidence.getAsJsonObject("telemetry").getAsJsonArray("cells").size());
	}

	@Test
	public void monthlyInspectionCreatesDraftAndStartsAllStagesAtomically() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var draft = store.create(new CreateCommand(stringId, monthlyConfiguration(), null, actor));
		assertEquals("DRAFT", draft.event().status().name());
		assertEquals(0, draft.event().eventVersion());
		assertNull(draft.event().currentStageId());
		assertTrue(draft.stages().isEmpty());

		assertThrows(CalibrationConflictException.class, () -> store.start(draft.event().id(), 1, actor));
		var afterStale = store.get(draft.event().id());
		assertTrue(afterStale.stages().isEmpty());
		assertEquals(0, afterStale.event().eventVersion());

		var active = store.start(draft.event().id(), 0, actor);
		assertEquals("ACTIVE", active.event().status().name());
		assertEquals(1, active.event().eventVersion());
		assertEquals(List.of(CalibrationStageType.PREREQUISITES, CalibrationStageType.MONTHLY_INSPECTION,
				CalibrationStageType.COMPLETION), active.stages().stream().map(stage -> stage.type()).toList());
		assertEquals(List.of("ACTIVE", "NOT_STARTED", "NOT_STARTED"),
				active.stages().stream().map(stage -> stage.state().name()).toList());
		assertEquals(active.stages().get(0).id(), active.event().currentStageId().longValue());
		assertEquals(1, store.history(draft.event().id()).transitions().size());
		assertThrows(CalibrationConflictException.class, () -> store.start(draft.event().id(), 0, actor));
		assertEquals(3, store.get(draft.event().id()).stages().size());
	}

	@Test
	public void deletesOnlyUnstartedUnauditedDraftAggregates() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long legacyDraftId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			legacyDraftId = insertDraft(connection, actorId, stringId);
			var stageId = insertStage(connection, legacyDraftId);
			try (var process = connection.prepareStatement("""
				INSERT INTO bms.calibration_process
					(calibration_event_id,calibration_stage_id,process_order,process_type,sampling_policy)
				VALUES (?,?,0,'LEGACY_UNUSED','NONE')
				""")) {
				process.setLong(1, legacyDraftId);
				process.setLong(2, stageId);
				process.executeUpdate();
			}
		}

		var store = store();
		assertThrows(SQLException.class, () -> {
			try (var connection = this.dataSource.getConnection()) {
				deleteClosedEvent(connection, legacyDraftId);
			}
		});
		try (var connection = this.dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var authorize = connection.prepareStatement(
					"SELECT set_config('bms.calibration.delete_draft_event_id',?,true)")) {
				authorize.setString(1, Long.toString(legacyDraftId + 1));
				authorize.execute();
			}
			assertThrows(SQLException.class, () -> deleteClosedEvent(connection, legacyDraftId));
			connection.rollback();
		}
		assertEquals(legacyDraftId, store.deleteDraft(new DeleteDraftCommand(legacyDraftId, 0)).calibrationEventId());
		assertThrows(IllegalArgumentException.class, () -> store.get(legacyDraftId));
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT (SELECT count(*) FROM bms.calibration_event WHERE id=?),
				(SELECT count(*) FROM bms.calibration_stage WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_process WHERE calibration_event_id=?)
			""")) {
			for (var index = 1; index <= 3; index++) query.setLong(index, legacyDraftId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
				assertEquals(0, result.getInt(2));
				assertEquals(0, result.getInt(3));
			}
		}

		var stale = store.create(new CreateCommand(stringId, monthlyConfiguration(), null, operator(actorId)));
		assertThrows(CalibrationConflictException.class,
				() -> store.deleteDraft(new DeleteDraftCommand(stale.event().id(), 1)));
		assertEquals(0, store.get(stale.event().id()).event().eventVersion());

		var active = store.start(stale.event().id(), 0, operator(actorId));
		assertThrows(IllegalArgumentException.class,
				() -> store.deleteDraft(new DeleteDraftCommand(active.event().id(), active.event().eventVersion())));
		try (var connection = this.dataSource.getConnection(); var close = connection.prepareStatement("""
			UPDATE bms.calibration_event SET status='CANCELLED',current_stage_id=NULL,closed_at=now() WHERE id=?
			""")) {
			close.setLong(1, active.event().id());
			close.executeUpdate();
		}
		assertThrows(IllegalArgumentException.class,
				() -> store.deleteDraft(new DeleteDraftCommand(active.event().id(), active.event().eventVersion())));
		assertEquals("CANCELLED", store.get(active.event().id()).event().status().name());

		long auditedDraftId;
		try (var connection = this.dataSource.getConnection()) {
			auditedDraftId = insertDraft(connection, actorId, stringId);
			var stageId = insertStage(connection, auditedDraftId);
			insertSample(connection, auditedDraftId, stageId, UUID.randomUUID(), 1);
		}
		assertThrows(IllegalArgumentException.class, () -> store.deleteDraft(new DeleteDraftCommand(auditedDraftId, 0)));
		assertEquals(1, store.get(auditedDraftId).stages().size());
	}

	@Test
	public void redoEarlierCompletedStageIsVersionedAuditedAndResetsLaterStages() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long eventId;
		long targetStageId;
		long currentStageId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			try (var event = connection.prepareStatement("""
				INSERT INTO bms.calibration_event(string_id,event_type,status,configuration_snapshot,
					event_version,created_by,created_by_name) VALUES (?,'MONTHLY_INSPECTION','DRAFT',?::jsonb,0,?,'Operator Name')
				RETURNING id
				""")) {
				event.setObject(1, stringId);
				event.setString(2, GSON.toJson(monthlyConfiguration()));
				event.setObject(3, actorId);
				try (var result = event.executeQuery()) { result.next(); eventId = result.getLong(1); }
			}
			try (var stage = connection.prepareStatement("""
				INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type,state,result,properties,
					started_at,completed_at,completed_by)
				VALUES (?,0,'PREREQUISITES','COMPLETED','PASS','{"manualEvidenceTypes":["PREREQUISITES_CONFIRMATION"]}',now(),now(),?) RETURNING id
				""")) {
				stage.setLong(1, eventId); stage.setObject(2, actorId);
				try (var result = stage.executeQuery()) { result.next(); targetStageId = result.getLong(1); }
			}
			try (var transition = connection.prepareStatement("""
				INSERT INTO bms.calibration_transition
					(calibration_event_id,calibration_stage_id,from_state,to_state,action,actor_id,actor_name,
					 evidence,resulting_event_version)
				VALUES (?,?,'ACTIVE','COMPLETED','COMPLETE_STAGE',?,'Operator Name','{}'::jsonb,4)
				"""); var evidence = connection.prepareStatement("""
				INSERT INTO bms.calibration_manual_evidence
					(calibration_event_id,calibration_stage_id,evidence_type,status,actor_id,actor_name)
				VALUES (?,?,'PREREQUISITES_CONFIRMATION','PASS',?,'Operator Name')
				""")) {
				transition.setLong(1, eventId); transition.setLong(2, targetStageId); transition.setObject(3, actorId);
				transition.executeUpdate();
				evidence.setLong(1, eventId); evidence.setLong(2, targetStageId); evidence.setObject(3, actorId);
				evidence.executeUpdate();
			}
			try (var stage = connection.prepareStatement("""
				INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type,state,properties,started_at)
				VALUES (?,1,'MONTHLY_INSPECTION','ACTIVE','{"checkpoint":"old"}',now()) RETURNING id
				""")) {
				stage.setLong(1, eventId);
				try (var result = stage.executeQuery()) { result.next(); currentStageId = result.getLong(1); }
			}
			try (var stage = connection.prepareStatement("""
				INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type,state)
				VALUES (?,2,'COMPLETION','NOT_STARTED')
				""")) { stage.setLong(1, eventId); stage.executeUpdate(); }
			try (var update = connection.prepareStatement("""
				UPDATE bms.calibration_event SET status='ACTIVE',current_stage_id=?,event_version=5,started_at=now() WHERE id=?
				""")) {
				update.setLong(1, currentStageId); update.setLong(2, eventId); update.executeUpdate();
			}
		}
		assertThrows(SQLException.class, () -> {
			try (var connection = this.dataSource.getConnection(); var forbidden = connection.prepareStatement(
					"UPDATE bms.calibration_stage SET properties='{}'::jsonb WHERE id=?")) {
				forbidden.setLong(1, targetStageId);
				forbidden.executeUpdate();
			}
		});
		var store = store();
		var redone = store.redoStage(new RedoStageCommand(eventId, targetStageId, 5, "Repeat after repair"),
				operator(actorId));
		assertEquals(6, redone.event().eventVersion());
		assertEquals(targetStageId, redone.event().currentStageId().longValue());
		assertEquals(StageState.ACTIVE, redone.stages().get(0).state());
		assertEquals(1, redone.stages().get(0).properties().getAsJsonObject().get("attemptNumber").getAsInt());
		assertFalse(redone.stages().get(0).properties().getAsJsonObject().has("manualEvidenceTypes"));
		assertNull(redone.stages().get(0).acceptedAction());
		assertEquals(StageState.NOT_STARTED, redone.stages().get(1).state());
		assertTrue(redone.stages().get(1).properties().getAsJsonObject().isEmpty());
		assertEquals("REDO_STAGE", store.history(eventId).transitions().get(1).action());
		assertEquals(1, store.history(eventId).evidence().size());
		assertThrows(CalibrationConflictException.class, () -> store.redoStage(
				new RedoStageCommand(eventId, targetStageId, 5, "stale"), operator(actorId)));
		var afterStale = store.get(eventId);
		assertEquals(6, afterStale.event().eventVersion());
		assertEquals(targetStageId, afterStale.event().currentStageId().longValue());
		assertEquals(2, store.history(eventId).transitions().size());
		try (var connection = this.dataSource.getConnection(); var close = connection.prepareStatement("""
			UPDATE bms.calibration_event SET status='COMPLETED',current_stage_id=NULL,closed_at=now() WHERE id=?
			""")) {
			close.setLong(1, eventId);
			close.executeUpdate();
		}
		assertThrows(CalibrationConflictException.class, () -> store.redoStage(
				new RedoStageCommand(eventId, targetStageId, 6, "closed"), operator(actorId)));
		assertEquals(2, store.history(eventId).transitions().size());
	}

	@Test
	public void finalReportUsesBoundedAuditManifestInsteadOfEmbeddingAllSamples() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long eventId;
		long stageId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			eventId = insertDraft(connection, actorId, stringId);
			stageId = insertStage(connection, eventId);
			var generation = UUID.randomUUID();
			try (var samples = connection.prepareStatement("""
				INSERT INTO bms.calibration_sample
					(calibration_event_id,calibration_stage_id,snapshot_generation,snapshot_sequence,
					 measurement_time,persistence_reason,quality,string_voltage_v,midpoint_voltage_v,
					 ambient_temperature_c)
				SELECT ?,?,?,sequence,now() + sequence * interval '1 second','PERIODIC_SAMPLE','VALID',
					4.6,2.3,25.0
				FROM generate_series(1,200) sequence
				""")) {
				samples.setLong(1, eventId);
				samples.setLong(2, stageId);
				samples.setObject(3, generation);
				assertEquals(200, samples.executeUpdate());
			}
			try (var cells = connection.prepareStatement("""
				INSERT INTO bms.calibration_cell_sample(calibration_sample_id,cell_index,voltage_v,quality)
				SELECT sample.id,cell_index,2.3,'VALID'
				FROM bms.calibration_sample sample CROSS JOIN generate_series(1,2) cell_index
				WHERE sample.calibration_event_id=?
				""")) {
				cells.setLong(1, eventId);
				assertEquals(400, cells.executeUpdate());
			}

			var method = CalibrationEventStore.class.getDeclaredMethod("buildReport", Connection.class,
					long.class, long.class);
			method.setAccessible(true);
			var report = (com.google.gson.JsonObject) method.invoke(null, connection, eventId, 1L);
			assertEquals(2, report.get("reportSchemaVersion").getAsInt());
			assertFalse(report.has("samples"));
			assertFalse(report.has("transitions"));
			assertFalse(report.has("manualEvidence"));
			var manifest = report.getAsJsonObject("auditManifest");
			assertEquals(200, manifest.get("sampleCount").getAsInt());
			assertEquals(400, manifest.get("cellSampleCount").getAsInt());
			assertEquals(2, report.getAsJsonObject("latestTelemetryEvidence")
					.getAsJsonArray("cells").size());
			assertTrue("Report must remain bounded as audit history grows", GSON.toJson(report).length() < 50_000);
		}
	}

	@Test
	public void retainedCurrentDoesNotPersistUntilStaleAndFreshCurrentRecovers() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var active = store.start(store.create(new CreateCommand(stringId, monthlyConfiguration(), null,
				operator(actorId))).event().id(), 0, operator(actorId));
		long eventId = active.event().id();
		long stageId = active.event().currentStageId();
		var worker = new WorkerEvent(eventId, stageId, stringId, "str1", monthlyConfiguration(), 1,
				CalibrationStageType.PREREQUISITES, StageState.ACTIVE, active.stages().get(0).properties(),
				null, null, null, null, null, null, null, null);
		long currentMeasuredAt = System.currentTimeMillis();

		assertTrue(store.persistWorkerEvaluation(worker, sampleWrite(worker, 1, currentMeasuredAt,
				currentMeasuredAt, CurrentQuality.FRESH, List.of(), "fresh")));
		assertFalse(store.persistWorkerEvaluation(worker, sampleWrite(worker, 2, currentMeasuredAt + 1_000,
				currentMeasuredAt, CurrentQuality.RETAINED,
				List.of(CalibrationBlocker.CURRENT_QUALITY_INVALID), "retained")));
		assertWorkerState(eventId, stageId, 1, 1, "ACTIVE", null, "fresh");

		long staleAt = currentMeasuredAt + monthlyConfiguration().snapshotFreshnessSeconds() * 1_000L;
		assertTrue(store.persistWorkerEvaluation(worker, sampleWrite(worker, 3, staleAt, currentMeasuredAt,
				CurrentQuality.RETAINED, List.of(CalibrationBlocker.CURRENT_STALE), "stale")));
		// A repeated blocker with a newer current timestamp must still be suppressed;
		// this exercises the persisted blocker signature rather than retained-current
		// timestamp dedupe.
		assertFalse(store.persistWorkerEvaluation(worker, sampleWrite(worker, 4, staleAt + 1,
				currentMeasuredAt + 1, CurrentQuality.FRESH,
				List.of(CalibrationBlocker.CURRENT_STALE), "stale-repeat")));
		assertWorkerState(eventId, stageId, 2, 2, "BLOCKED", "CURRENT_STALE", "stale");

		assertTrue(store.persistWorkerEvaluation(worker, sampleWrite(worker, 5, staleAt + 1_000,
				staleAt + 1_000, CurrentQuality.FRESH, List.of(), "recovered")));
		assertWorkerState(eventId, stageId, 3, 3, "ACTIVE", null, "recovered");
		assertEquals(List.of("VALID", "CURRENT_UNAVAILABLE", "VALID"), store.samples(eventId).stream()
				.map(sample -> sample.quality()).toList());
	}

	@Test
	public void dischargeRuntimeAdvancesWithoutFullSampleAndExceptionalWriteDoesNotMovePeriodicClock()
			throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var prerequisites = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.PREREQUISITES).findFirst().orElseThrow();
		active = store.configureDemoTimeScale(
				new ConfigureDemoTimeScaleCommand(active.event().id(), prerequisites.id(), 1, 60), actor);
		var dischargeStage = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.DISCHARGE).findFirst().orElseThrow();
		var dischargeProcess = active.processes().stream()
				.filter(value -> value.calibrationStageId() == dischargeStage.id()
						&& "CONTROLLED_DISCHARGE".equals(value.type()))
				.findFirst().orElseThrow();
		activateStage(active.event().id(), dischargeStage.id());
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}
		long eventId = active.event().id();
		long stageId = dischargeStage.id();
		long processId = dischargeProcess.id();
		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == eventId && value.processId() == processId)
				.findFirst().orElseThrow();
		var generation = UUID.randomUUID();
		long firstAt = System.currentTimeMillis();
		var monitor = new io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor();
		var firstMeasurement = dischargeMeasurement(worker, generation, 1, firstAt);
		var firstEvaluation = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				firstMeasurement, null, firstAt, 60);
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(firstMeasurement, firstEvaluation, "VALID", null, firstAt)));

		var reloaded = store.activeWorkerEvents().stream().filter(value -> value.eventId() == eventId)
				.findFirst().orElseThrow();
		assertTrue(reloaded.runtimeCheckpoint().isJsonObject());
		assertFalse(reloaded.processProperties().getAsJsonObject().has("checkpoint"));
		var checkpoint = GSON.fromJson(reloaded.runtimeCheckpoint(),
				io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint.class);
		var secondAt = firstAt + 1_000;
		var secondMeasurement = dischargeMeasurement(worker, generation, 2, secondAt);
		var secondEvaluation = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				secondMeasurement, checkpoint, secondAt, 60);
		assertFalse(store.persistWorkerEvaluation(worker,
				new SampleWrite(secondMeasurement, secondEvaluation, "VALID", null, secondAt)));

		// The calibration worker can observe the same immutable calculator snapshot
		// more than once. The monitor identifies that as a repeated sequence, but the
		// persistence boundary must suppress it instead of alternating the durable
		// quality signature INVALID -> VALID on every new BMS publication.
		var repeatedEvaluation = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				secondMeasurement, secondEvaluation.checkpoint(), secondAt + 1_000, 60);
		assertTrue(repeatedEvaluation.blockers()
				.contains(CalibrationBlocker.MEASUREMENT_SEQUENCE_REPEATED));
		assertFalse(store.persistWorkerEvaluation(worker,
				new SampleWrite(secondMeasurement, repeatedEvaluation, "INVALID",
						"MEASUREMENT_SEQUENCE_REPEATED", secondAt + 1_000)));

		var blockedEvaluation = new CalibrationStageEvaluation<>(secondEvaluation.checkpoint(),
				List.of(CalibrationBlocker.CURRENT_STALE), List.of(), List.of(PersistenceReason.QUALITY_CHANGE),
				Recommendation.BLOCKED);
		var blockedAt = firstAt + 1_500;
		var blockedMeasurement = dischargeMeasurement(worker, generation, 3, blockedAt);
		assertTrue(store.persistWorkerEvaluation(worker, new SampleWrite(blockedMeasurement, blockedEvaluation,
				"CURRENT_UNAVAILABLE", "CURRENT_STALE", blockedAt)));

		var finalAt = firstAt + 2_000;
		var finalMeasurement = dischargeMeasurement(worker, generation, 4, finalAt);
		var finalEvaluation = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				finalMeasurement, secondEvaluation.checkpoint(), finalAt, 60);
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(finalMeasurement, finalEvaluation, "VALID", null, finalAt)));
		var persistedProcess = store.get(eventId).processes().stream().filter(value -> value.id() == processId)
				.findFirst().orElseThrow();
		assertEquals(4, persistedProcess.runtime().getAsJsonObject().get("snapshotSequence").getAsLong());
		assertTrue(persistedProcess.runtime().getAsJsonObject().get("updatedAt").getAsString().endsWith("Z"));
		assertNull(persistedProcess.runtime().getAsJsonObject().get("rawUsedAh"));
		assertTrue(persistedProcess.dischargeRuntime().isJsonObject());
		assertTrue(persistedProcess.dischargeRuntime().getAsJsonObject().get("rawUsedAh").getAsDouble() > 0.0);

		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT runtime.snapshot_sequence,discharge.raw_used_ah,runtime.last_periodic_persisted_at,
				(SELECT count(*) FROM jsonb_object_keys(runtime.latest_measurement->'cells')),
				(runtime.latest_measurement->>'stringVoltageV')::numeric,
				(runtime.latest_measurement #>> '{cells,1,voltageV}')::numeric,
				jsonb_exists(process.properties,'latestMeasurement'),
				(SELECT count(*) FROM bms.calibration_sample WHERE calibration_process_id=?),
				(SELECT count(*) FROM bms.calibration_sample_reason reason JOIN bms.calibration_sample sample
					ON sample.id=reason.calibration_sample_id WHERE sample.calibration_process_id=?)
			FROM bms.calibration_process_runtime runtime
			JOIN bms.calibration_discharge_runtime discharge
				ON discharge.calibration_process_id=runtime.calibration_process_id
				AND discharge.attempt_number=runtime.attempt_number
			JOIN bms.calibration_process process ON process.id=runtime.calibration_process_id
			WHERE runtime.calibration_process_id=? AND runtime.attempt_number=0
			""")) {
			query.setLong(1, processId);
			query.setLong(2, processId);
			query.setLong(3, processId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(4, result.getLong(1));
				assertTrue(result.getDouble(2) > 0.0);
				assertEquals(finalAt, result.getTimestamp(3).toInstant().toEpochMilli());
				assertEquals(2, result.getInt(4));
				assertEquals(4.61, result.getDouble(5), 1e-9);
				assertEquals(2.30, result.getDouble(6), 1e-9);
				assertFalse(result.getBoolean(7));
				assertEquals(3, result.getInt(8));
				assertEquals(4, result.getInt(9));
			}
		}

		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement(
				"UPDATE bms.calibration_process SET attempt_number=1 WHERE id=?")) {
			update.setLong(1, processId);
			assertEquals(1, update.executeUpdate());
		}
		var currentAttempt = store.get(eventId).processes().stream().filter(value -> value.id() == processId)
				.findFirst().orElseThrow();
		assertEquals(1, currentAttempt.attemptNumber());
		assertNull(currentAttempt.runtime());
		assertNull(currentAttempt.dischargeRuntime());
		var staleAt = finalAt + 2_000;
		var staleMeasurement = dischargeMeasurement(worker, generation, 5, staleAt);
		var staleEvaluation = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				staleMeasurement, finalEvaluation.checkpoint(), staleAt, 60);
		assertFalse(store.persistWorkerEvaluation(worker,
				new SampleWrite(staleMeasurement, staleEvaluation, "VALID", null, staleAt)));
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT snapshot_sequence FROM bms.calibration_process_runtime
			WHERE calibration_process_id=? AND attempt_number=0
			""")) {
			query.setLong(1, processId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(4, result.getLong(1));
			}
		}
	}

	@Test
	public void wrongCurrentDirectionCounterSurvivesRuntimeReloadAndBlocksAfterConfiguredMaximum()
			throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var waitingForLoad = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.WAITING_FOR_LOAD).findFirst().orElseThrow();
		activateStage(active.event().id(), waitingForLoad.id());
		active = store.configureDischargeMonitor(new ConfigureDischargeMonitorCommand(active.event().id(),
				waitingForLoad.id(), active.event().eventVersion(), 1.65, 2), actor);
		long eventId = active.event().id();
		var dischargeStage = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.DISCHARGE).findFirst().orElseThrow();
		var dischargeProcess = active.processes().stream()
				.filter(value -> value.calibrationStageId() == dischargeStage.id()
						&& "CONTROLLED_DISCHARGE".equals(value.type()))
				.findFirst().orElseThrow();
		activateStage(eventId, dischargeStage.id());
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}

		var monitor = new io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor();
		var generation = UUID.randomUUID();
		long measuredAt = System.currentTimeMillis();
		io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint checkpoint = null;
		for (int sequence = 1; sequence <= 2; sequence++) {
			var worker = store.activeWorkerEvents().stream()
					.filter(value -> value.eventId() == eventId).findFirst().orElseThrow();
			var measurement = dischargeMeasurement(worker, generation, sequence, measuredAt + sequence * 1_000L,
					-10.0, 2.30, 2.31);
			var evaluation = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE, measurement,
					checkpoint, measurement.currentMeasuredAt(), 1, 2);
			assertTrue(evaluation.blockers().isEmpty());
			assertFalse(store.persistWorkerEvaluation(worker,
					new SampleWrite(measurement, evaluation, "VALID", null, measurement.currentMeasuredAt())));
			var reloaded = store.activeWorkerEvents().stream()
					.filter(value -> value.eventId() == eventId).findFirst().orElseThrow();
			checkpoint = GSON.fromJson(reloaded.runtimeCheckpoint(),
					io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint.class);
			assertEquals(sequence, checkpoint.consecutiveWrongCurrentDirectionSamples());
		}

		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == eventId).findFirst().orElseThrow();
		var third = dischargeMeasurement(worker, generation, 3, measuredAt + 3_000L, -10.0, 2.30, 2.31);
		var blocked = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE, third, checkpoint,
				third.currentMeasuredAt(), 1, 2);
		assertTrue(blocked.blockers().contains(CalibrationBlocker.PHASE_CURRENT_INCONSISTENT));
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(third, blocked, "INVALID", "PHASE_CURRENT_INCONSISTENT", third.currentMeasuredAt())));
		var reloaded = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == eventId).findFirst().orElseThrow();
		var persisted = GSON.fromJson(reloaded.runtimeCheckpoint(),
				io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint.class);
		assertEquals(3, persisted.consecutiveWrongCurrentDirectionSamples());
	}

	@Test
	public void allCellsCompletionPublishesNormalizedPerCellCurveAndActivatesBaseline() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var dischargeStage = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.DISCHARGE).findFirst().orElseThrow();
		var dischargeProcess = active.processes().stream()
				.filter(value -> value.calibrationStageId() == dischargeStage.id()
						&& "CONTROLLED_DISCHARGE".equals(value.type()))
				.findFirst().orElseThrow();
		activateStage(active.event().id(), dischargeStage.id());
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}
		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var generation = UUID.randomUUID();
		long firstAt = System.currentTimeMillis();
		var monitor = new io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor();
		var anchorMeasurement = dischargeMeasurement(worker, generation, 1, firstAt, 2.30, 2.31);
		var anchor = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				anchorMeasurement, null, firstAt, 1);
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(anchorMeasurement, anchor, "VALID", null, firstAt)));

		long partialAt = firstAt + 2_000;
		var partialMeasurement = dischargeMeasurement(worker, generation, 2, partialAt, 1.79, 1.90);
		var partial = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				partialMeasurement, anchor.checkpoint(), partialAt, 1);
		assertEquals(Recommendation.CONTINUE, partial.recommendation());
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(partialMeasurement, partial, "VALID", null, partialAt)));
		// One source sample is simultaneously cell 1's cutoff and a rated-DOD
		// milestone. Publication must dedupe it, while cell 2 may still use it before
		// its later cutoff.
		try (var connection = this.dataSource.getConnection(); var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_sample_reason
				(calibration_sample_id,persistence_reason,reason_key,milestone_value)
			SELECT id,'DOD_MILESTONE_5_PERCENT','DOD_PERCENT:5.0',5.0
			FROM bms.calibration_sample
			WHERE calibration_process_id=? AND snapshot_generation=? AND snapshot_sequence=2
			""")) {
			insert.setLong(1, dischargeProcess.id());
			insert.setObject(2, generation);
			assertEquals(1, insert.executeUpdate());
		}

		var partialDetail = store.get(active.event().id());
		assertNull(partialDetail.referenceCurve());
		var partialProcess = partialDetail.processes().stream().filter(value -> value.id() == dischargeProcess.id())
				.findFirst().orElseThrow();
		assertEquals("ACTIVE", partialProcess.state().name());
		var partialRuntime = partialProcess.dischargeRuntime().getAsJsonObject();
		assertEquals(1, partialRuntime.get("crossedCellCount").getAsInt());
		assertEquals(2, partialRuntime.get("configuredCellCount").getAsInt());
		double firstCellCapacityAh = partial.checkpoint().perCellState().get(1).cellUsableCapacityAh();
		assertEquals(firstCellCapacityAh, partialRuntime.get("stringSafeUsableCapacityAh").getAsDouble(), 0.000001);
		assertEquals(firstCellCapacityAh, partialRuntime.get("allCellsEndpointAh").getAsDouble(), 0.000001);

		var restartedWorker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var restartedCheckpoint = GSON.fromJson(restartedWorker.runtimeCheckpoint(),
				io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint.class);
		assertTrue(Boolean.TRUE.equals(restartedCheckpoint.perCellState().get(1).cutoffReached()));

		long finalAt = partialAt + 2_000;
		var finalMeasurement = dischargeMeasurement(restartedWorker, generation, 3, finalAt, 1.76, 1.78);
		var endpoint = monitor.evaluate(restartedWorker.configuration(), CalibrationStageType.DISCHARGE,
				finalMeasurement, restartedCheckpoint, finalAt, 1);
		assertEquals(Recommendation.READY_TO_COMPLETE, endpoint.recommendation());
		var endpointWrite = new SampleWrite(finalMeasurement, endpoint, "VALID", null, finalAt);
		assertTrue(store.persistWorkerEvaluation(restartedWorker, endpointWrite));
		assertFalse(store.persistWorkerEvaluation(restartedWorker, endpointWrite));

		var completedDetail = store.get(active.event().id());
		var completedProcess = completedDetail.processes().stream()
				.filter(value -> value.id() == dischargeProcess.id()).findFirst().orElseThrow();
		assertEquals("COMPLETED", completedProcess.state().name());
		assertEquals("ACTIVE", completedDetail.stages().stream().filter(value -> value.id() == dischargeStage.id())
				.findFirst().orElseThrow().state().name());
		var completedRuntime = completedProcess.dischargeRuntime().getAsJsonObject();
		assertEquals(2, completedRuntime.get("crossedCellCount").getAsInt());
		assertEquals(firstCellCapacityAh, completedRuntime.get("stringSafeUsableCapacityAh").getAsDouble(), 0.000001);
		assertEquals(endpoint.checkpoint().rawUsedAh(),
				completedRuntime.get("allCellsEndpointAh").getAsDouble(), 0.000001);
		assertTrue(completedDetail.referenceCurve() != null);
		assertTrue(completedDetail.referenceCurve().activeBaseline());
		assertEquals(3, completedDetail.referenceCurve().pointCount());
		var curve = store.curvePoints(active.event().id(), null, null, 0, 100);
		assertEquals(3, curve.total());
		assertEquals(List.of(1, 2, 2), curve.points().stream().map(value -> value.cellIndex()).toList());
		assertEquals(List.of(0, 0, 1), curve.points().stream().map(value -> value.pointOrder()).toList());
		assertEquals(100.0, curve.points().get(0).normalizedActualDodPercent(), 0.000001);
		assertTrue(curve.points().get(1).normalizedActualDodPercent() < 100.0);
		assertEquals(100.0, curve.points().get(2).normalizedActualDodPercent(), 0.000001);
		assertEquals(partialMeasurement.cells().get(2).voltageV(), curve.points().get(1).cellVoltageV(), 0.0);
		assertTrue(curve.points().stream().allMatch(value -> value.cellSocPct() == null && value.cellSohPct() == null));
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT count(*) FILTER (WHERE sample.soc_pct IS NOT NULL),
				count(*) FILTER (WHERE cell.soc_pct IS NOT NULL OR cell.soh_pct IS NOT NULL)
			FROM bms.calibration_sample sample
			LEFT JOIN bms.calibration_cell_sample cell ON cell.calibration_sample_id=sample.id
			WHERE sample.calibration_event_id=?
			""")) {
			query.setLong(1, active.event().id());
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(0, result.getLong(1));
				assertEquals(0, result.getLong(2));
			}
		}
		var cellTwo = store.curvePoints(active.event().id(), completedDetail.referenceCurve().curveVersionId(), 2, 0, 1);
		assertEquals(2, cellTwo.total());
		assertEquals(1, cellTwo.points().size());
		assertEquals(2, cellTwo.points().get(0).cellIndex());

		// The reference loader validates the immutable curve before loading later
		// Recovery/Voc snapshots. Corrupt one stored point below the append-only API
		// contract and prove that a disposable PostgreSQL fixture is rejected.
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE bms.calibration_curve_point DISABLE TRIGGER USER");
			try (var complete = connection.prepareStatement("""
				UPDATE bms.calibration_event SET status='COMPLETED',current_stage_id=NULL,
					completed_at=now(),closed_at=now(),
					baseline_approved_at=now(),baseline_approved_by=?,baseline_approved_by_name='Operator Name'
				WHERE id=?
				"""); var corrupt = connection.prepareStatement("""
				UPDATE bms.calibration_curve_point SET cell_voltage_v=cell_voltage_v+0.001
				WHERE curve_version_id=? AND cell_index=1 AND point_order=0
				""")) {
				complete.setObject(1, actorId);
				complete.setLong(2, active.event().id());
				assertEquals(1, complete.executeUpdate());
				corrupt.setLong(1, completedDetail.referenceCurve().curveVersionId());
				assertEquals(1, corrupt.executeUpdate());
			} finally {
				statement.execute("ALTER TABLE bms.calibration_curve_point ENABLE TRIGGER USER");
			}
		}
		var referenceLoader = new InitialBaselineReferenceDatasetService();
		setField(referenceLoader, "database", new TestDatabase(this.dataSource));
		var corrupted = assertThrows(IllegalStateException.class,
				() -> referenceLoader.loadApprovedByEventId(active.event().id()));
		assertTrue(corrupted.getMessage().contains("canonical source sample"));
	}

	@Test
	public void simultaneousCellCutoffsShareSampleAndReplayIsIdempotent() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var dischargeStage = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.DISCHARGE).findFirst().orElseThrow();
		var dischargeProcess = active.processes().stream()
				.filter(value -> value.calibrationStageId() == dischargeStage.id()
						&& "CONTROLLED_DISCHARGE".equals(value.type()))
				.findFirst().orElseThrow();
		activateStage(active.event().id(), dischargeStage.id());
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now()
			WHERE id=? AND state='NOT_STARTED'
			""")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}
		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var generation = UUID.randomUUID();
		long firstAt = System.currentTimeMillis();
		var monitor = new io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor();
		var anchorMeasurement = dischargeMeasurement(worker, generation, 1, firstAt, 2.30, 2.31);
		var anchor = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				anchorMeasurement, null, firstAt, 1);
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(anchorMeasurement, anchor, "VALID", null, firstAt)));

		long cutoffAt = firstAt + 2_000;
		var cutoffMeasurement = dischargeMeasurement(worker, generation, 2, cutoffAt, 1.79, 1.77);
		var cutoff = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				cutoffMeasurement, anchor.checkpoint(), cutoffAt, 1);
		var cutoffWrite = new SampleWrite(cutoffMeasurement, cutoff, "VALID", null, cutoffAt);
		assertEquals(2, cutoff.checkpoint().newCutoffEndpoints().size());
		assertTrue(store.persistWorkerEvaluation(worker, cutoffWrite));
		assertFalse(store.persistWorkerEvaluation(worker, cutoffWrite));

		var detail = store.get(active.event().id());
		var reloaded = detail.processes().stream().filter(value -> value.id() == dischargeProcess.id())
				.findFirst().orElseThrow();
		assertEquals("COMPLETED", reloaded.state().name());
		assertEquals("ACTIVE", detail.stages().stream().filter(value -> value.id() == dischargeStage.id())
				.findFirst().orElseThrow().state().name());
		var dischargeRuntime = reloaded.dischargeRuntime().getAsJsonObject();
		assertEquals(2, dischargeRuntime.get("crossedCellCount").getAsInt());
		assertEquals(2, dischargeRuntime.get("configuredCellCount").getAsInt());
		assertEquals(dischargeRuntime.get("stringSafeUsableCapacityAh").getAsDouble(),
				dischargeRuntime.get("allCellsEndpointAh").getAsDouble(), 0.000001);
		var checkpoint = dischargeRuntime.getAsJsonObject("perCellState");
		assertEquals(2, checkpoint.size());
		assertTrue(checkpoint.getAsJsonObject("1").get("cutoffReached").getAsBoolean());
		assertEquals(1.79, checkpoint.getAsJsonObject("1").get("cutoffVoltageV").getAsDouble(), 0.000001);

		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT count(*),count(DISTINCT after_sample_id),count(before_sample_id),
				count(interpolation_fraction),min(cutoff_voltage_v),max(cutoff_voltage_v),
				count(DISTINCT cell_usable_capacity_ah)
			FROM bms.calibration_cell_cutoff_crossing
			WHERE calibration_process_id=? AND process_attempt_number=0
			""")) {
			query.setLong(1, dischargeProcess.id());
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(2, result.getInt(1));
				assertEquals(1, result.getInt(2));
				assertEquals(0, result.getInt(3));
				assertEquals(0, result.getInt(4));
				assertEquals(1.77, result.getDouble(5), 0.000001);
				assertEquals(1.79, result.getDouble(6), 0.000001);
				assertEquals(1, result.getInt(7));
			}
		}
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT count(*) FROM bms.calibration_process_transition
			WHERE calibration_process_id=? AND action='AUTO_ALL_CELLS_CUTOFF'
			""")) {
			query.setLong(1, dischargeProcess.id());
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(1, result.getInt(1));
			}
		}
	}

	@Test
	public void invalidCurveEvidenceRollsBackAllCellsCompletionAndPublication() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) { seed(connection, actorId, stringId); }
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var dischargeStage = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.DISCHARGE).findFirst().orElseThrow();
		var dischargeProcess = active.processes().stream()
				.filter(value -> value.calibrationStageId() == dischargeStage.id()
						&& "CONTROLLED_DISCHARGE".equals(value.type()))
				.findFirst().orElseThrow();
		activateStage(active.event().id(), dischargeStage.id());
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now() WHERE id=?
			""")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}
		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var generation = UUID.randomUUID();
		long firstAt = System.currentTimeMillis();
		var monitor = new io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor();
		var anchorMeasurement = dischargeMeasurement(worker, generation, 1, firstAt, 2.30, 2.31);
		var anchor = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				anchorMeasurement, null, firstAt, 1);
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(anchorMeasurement, anchor, "VALID", null, firstAt)));
		long partialAt = firstAt + 2_000;
		var partialMeasurement = dischargeMeasurement(worker, generation, 2, partialAt, 1.79, 1.90);
		var partial = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				partialMeasurement, anchor.checkpoint(), partialAt, 1);
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(partialMeasurement, partial, "VALID", null, partialAt)));
		// Simulate storage corruption below the append-only API contract. The trigger is
		// intentionally bypassed only for this fixture setup and restored immediately.
		try (var connection = this.dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE bms.calibration_cell_cutoff_crossing DISABLE TRIGGER USER");
			try (var corrupt = connection.prepareStatement("""
				UPDATE bms.calibration_cell_cutoff_crossing SET cell_usable_capacity_ah=0
				WHERE calibration_process_id=? AND process_attempt_number=0 AND cell_index=1
				""")) {
				corrupt.setLong(1, dischargeProcess.id());
				assertEquals(1, corrupt.executeUpdate());
			} finally {
				statement.execute("ALTER TABLE bms.calibration_cell_cutoff_crossing ENABLE TRIGGER USER");
			}
		}
		var restarted = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var checkpoint = GSON.fromJson(restarted.runtimeCheckpoint(),
				io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint.class);
		long finalAt = partialAt + 2_000;
		var finalMeasurement = dischargeMeasurement(restarted, generation, 3, finalAt, 1.76, 1.78);
		var endpoint = monitor.evaluate(restarted.configuration(), CalibrationStageType.DISCHARGE,
				finalMeasurement, checkpoint, finalAt, 1);
		assertThrows(IllegalStateException.class, () -> store.persistWorkerEvaluation(restarted,
				new SampleWrite(finalMeasurement, endpoint, "VALID", null, finalAt)));

		var rolledBack = store.get(active.event().id());
		assertNull(rolledBack.referenceCurve());
		assertEquals(ProcessState.ACTIVE, rolledBack.processes().stream()
				.filter(value -> value.id() == dischargeProcess.id()).findFirst().orElseThrow().state());
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT (SELECT count(*) FROM bms.calibration_curve_version WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_active_baseline WHERE string_id=?),
				(SELECT count(*) FROM bms.calibration_cell_cutoff_crossing WHERE calibration_process_id=?)
			""")) {
			query.setLong(1, active.event().id());
			query.setObject(2, stringId);
			query.setLong(3, dischargeProcess.id());
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
				assertEquals(0, result.getInt(2));
				assertEquals(1, result.getInt(3));
			}
		}
	}

	@Test
	public void abortedInitialBaselineNeverPublishesReferenceCurve() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		try (var connection = this.dataSource.getConnection(); var abort = connection.prepareStatement("""
			UPDATE bms.calibration_event
			SET status='ABORTED',current_stage_id=NULL,closed_at=now(),updated_at=now()
			WHERE id=?
			""")) {
			abort.setLong(1, active.event().id());
			assertEquals(1, abort.executeUpdate());
		}
		var aborted = store.get(active.event().id());
		assertEquals(EventStatus.ABORTED, aborted.event().status());
		assertNull(aborted.referenceCurve());
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT (SELECT count(*) FROM bms.calibration_curve_version WHERE calibration_event_id=?),
				(SELECT count(*) FROM bms.calibration_active_baseline WHERE calibration_event_id=?)
			""")) {
			query.setLong(1, active.event().id());
			query.setLong(2, active.event().id());
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(0, result.getInt(1));
				assertEquals(0, result.getInt(2));
			}
		}
	}

	@Test
	public void safetyFloorWinsOverAllCellsFreezesCapacityAndConfirmsOnlyAfterFreshZeroCurrent() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) { seed(connection, actorId, stringId); }
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var dischargeStage = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.DISCHARGE).findFirst().orElseThrow();
		var dischargeProcess = active.processes().stream()
				.filter(value -> value.calibrationStageId() == dischargeStage.id()
						&& "CONTROLLED_DISCHARGE".equals(value.type())).findFirst().orElseThrow();
		activateStage(active.event().id(), dischargeStage.id());
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement(
				"UPDATE bms.calibration_process SET state='ACTIVE',started_at=now() WHERE id=?")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}
		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var monitor = new io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor();
		var generation = UUID.randomUUID();
		long anchorAt = System.currentTimeMillis();
		var anchorMeasurement = dischargeMeasurement(worker, generation, 1, anchorAt, 10.0, 2.30, 2.31);
		var anchor = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				anchorMeasurement, null, anchorAt, 1);
		assertTrue(store.persistWorkerEvaluation(worker, new SampleWrite(anchorMeasurement, anchor, "VALID", null, anchorAt)));

		long safetyAt = anchorAt + 2_000;
		var safetyMeasurement = dischargeMeasurement(worker, generation, 2, safetyAt, 10.0, 1.70, 1.69);
		var safety = monitor.evaluate(worker.configuration(), CalibrationStageType.DISCHARGE,
				safetyMeasurement, anchor.checkpoint(), safetyAt, 1);
		assertEquals(Recommendation.CONTINUE, safety.recommendation());
		assertTrue(safety.persistenceReasons().contains(PersistenceReason.SAFETY_FLOOR));
		assertTrue(store.persistWorkerEvaluation(worker,
				new SampleWrite(safetyMeasurement, safety, "VALID", null, safetyAt)));

		var latched = store.get(active.event().id());
		assertNull(latched.referenceCurve());
		var latchedProcess = latched.processes().stream().filter(value -> value.id() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var runtime = latchedProcess.dischargeRuntime().getAsJsonObject();
		assertEquals(ProcessState.ACTIVE, latchedProcess.state());
		assertEquals("LATCHED", runtime.get("safetyState").getAsString());
		assertEquals(2, runtime.get("crossedCellCount").getAsInt());
		assertEquals(2, runtime.getAsJsonArray("safetyTriggerCells").size());
		assertFalse(runtime.get("canConfirmDischargeStopped").getAsBoolean());
		double frozenAh = runtime.get("frozenUsedAh").getAsDouble();
		assertEquals(frozenAh, runtime.get("officialUsedAh").getAsDouble(), 0.000001);
		var restartedWorker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId() == dischargeProcess.id())
				.findFirst().orElseThrow();
		var restartedCheckpoint = GSON.fromJson(restartedWorker.runtimeCheckpoint(),
				io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.Checkpoint.class);
		assertEquals(io.openems.edge.bms.service.calibration.implement.monitors.DischargeStageMonitor.SafetyState.LATCHED,
				restartedCheckpoint.safetyState());
		assertThrows(CalibrationConflictException.class, () -> store.confirmDischargeStopped(
				new ConfirmDischargeStoppedCommand(active.event().id(), dischargeStage.id(),
						dischargeProcess.id(), latched.event().eventVersion() + 1), actor));
		assertThrows(IllegalArgumentException.class, () -> store.confirmDischargeStopped(
				new ConfirmDischargeStoppedCommand(active.event().id(), dischargeStage.id(),
						dischargeProcess.id() + 99, latched.event().eventVersion()), actor));
		assertThrows(IllegalArgumentException.class, () -> store.confirmDischargeStopped(
				new ConfirmDischargeStoppedCommand(active.event().id(), dischargeStage.id(),
						dischargeProcess.id(), latched.event().eventVersion()), actor));
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process_runtime
			SET last_current_measured_at=now()-interval '1 hour',last_domain_current_a=0
			WHERE calibration_process_id=? AND attempt_number=0
			""")) {
			update.setLong(1, dischargeProcess.id());
			assertEquals(1, update.executeUpdate());
		}
		assertThrows(IllegalArgumentException.class, () -> store.confirmDischargeStopped(
				new ConfirmDischargeStoppedCommand(active.event().id(), dischargeStage.id(),
						dischargeProcess.id(), latched.event().eventVersion()), actor));

		long stoppedAt = safetyAt + 1_000;
		var stoppedMeasurement = dischargeMeasurement(worker, generation, 3, stoppedAt, 0.0, 1.69, 1.68);
		var stopped = monitor.evaluate(restartedWorker.configuration(), CalibrationStageType.DISCHARGE,
				stoppedMeasurement, restartedCheckpoint, stoppedAt, 1);
		store.persistWorkerEvaluation(worker, new SampleWrite(stoppedMeasurement, stopped, "VALID", null, stoppedAt));
		var ready = store.get(active.event().id());
		assertTrue(ready.processes().stream().filter(value -> value.id() == dischargeProcess.id()).findFirst().orElseThrow()
				.dischargeRuntime().getAsJsonObject().get("canConfirmDischargeStopped").getAsBoolean());

		var confirmed = store.confirmDischargeStopped(new ConfirmDischargeStoppedCommand(
				active.event().id(), dischargeStage.id(), dischargeProcess.id(), ready.event().eventVersion()), actor);
		assertEquals(EventStatus.BLOCKED, confirmed.event().status());
		assertEquals(StageState.BLOCKED, confirmed.stages().stream().filter(value -> value.id() == dischargeStage.id())
				.findFirst().orElseThrow().state());
		var confirmedProcess = confirmed.processes().stream().filter(value -> value.id() == dischargeProcess.id())
				.findFirst().orElseThrow();
		assertEquals(ProcessState.BLOCKED, confirmedProcess.state());
		assertEquals("STOP_CONFIRMED", confirmedProcess.dischargeRuntime().getAsJsonObject()
				.get("safetyState").getAsString());
		assertEquals(frozenAh, confirmedProcess.dischargeRuntime().getAsJsonObject().get("officialUsedAh").getAsDouble(),
				0.000001);
		assertThrows(IllegalArgumentException.class, () -> store.confirmDischargeStopped(
				new ConfirmDischargeStoppedCommand(active.event().id(), dischargeStage.id(),
						dischargeProcess.id(), confirmed.event().eventVersion()), actor));
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT count(*) FROM bms.calibration_process_transition
			WHERE calibration_process_id=? AND action='CONFIRM_DISCHARGE_STOPPED'
			""")) {
			query.setLong(1, dischargeProcess.id());
			try (var result = query.executeQuery()) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
		}
	}

	@Test
	public void activeWorkerEventLoadsPreviousCompletedMonthlyFloatCurrent() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long previousEventId;
		long measuredAt = System.currentTimeMillis() - 60_000;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			try (var event = connection.prepareStatement("""
				INSERT INTO bms.calibration_event
					(string_id,event_type,status,configuration_snapshot,created_by,created_by_name,closed_at)
				VALUES (?,'MONTHLY_INSPECTION','COMPLETED',?::jsonb,?,'Operator Name',now()) RETURNING id
				""")) {
				event.setObject(1, stringId);
				event.setString(2, GSON.toJson(monthlyConfiguration()));
				event.setObject(3, actorId);
				try (var result = event.executeQuery()) {
					result.next();
					previousEventId = result.getLong(1);
				}
			}
			long stageId;
			try (var stage = connection.prepareStatement("""
				INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type,state)
				VALUES (?,1,'MONTHLY_INSPECTION','COMPLETED') RETURNING id
				""")) {
				stage.setLong(1, previousEventId);
				try (var result = stage.executeQuery()) {
					result.next();
					stageId = result.getLong(1);
				}
			}
			try (var sample = connection.prepareStatement("""
				INSERT INTO bms.calibration_sample
					(calibration_event_id,calibration_stage_id,snapshot_generation,snapshot_sequence,
					 measurement_time,persistence_reason,quality,domain_current_a,current_measured_at)
				VALUES (?,?,?,1,?,'PERIODIC_SAMPLE','VALID',-0.125,?)
				""")) {
				sample.setLong(1, previousEventId);
				sample.setLong(2, stageId);
				sample.setObject(3, UUID.randomUUID());
				sample.setTimestamp(4, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(measuredAt)));
				sample.setTimestamp(5, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(measuredAt)));
				sample.executeUpdate();
			}
		}

		var store = store();
		var current = store.start(store.create(new CreateCommand(stringId, monthlyConfiguration(), null,
				operator(actorId))).event().id(), 0, operator(actorId));
		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == current.event().id()).findFirst().orElseThrow();
		assertEquals(previousEventId, worker.previousMonthlyEventId().longValue());
		assertEquals(0.125, worker.previousMonthlyFloatCurrentA(), 0.000001);
		assertEquals(measuredAt, worker.previousMonthlyMeasuredAt().longValue());
	}

	@Test
	public void incompatibleLegacyActiveEventIsSkippedWithoutBlockingHealthyWorkerEvent() throws Exception {
		var actorId = UUID.randomUUID();
		var healthyStringId = UUID.randomUUID();
		var legacyStringId = UUID.randomUUID();
		long legacyEventId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, healthyStringId);
			try (var string = connection.prepareStatement("""
				INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count,capacity_ah)
				VALUES (?,2,'str2','String 2',2,200)
				""")) {
				string.setObject(1, legacyStringId);
				string.executeUpdate();
			}
			long legacyStageId;
			try (var event = connection.prepareStatement("""
				INSERT INTO bms.calibration_event
					(string_id,event_type,status,configuration_snapshot,created_by,created_by_name)
				VALUES (?,'INITIAL_BASELINE','DRAFT',?::jsonb,?,'Operator Name') RETURNING id
				""")) {
				event.setObject(1, legacyStringId);
				event.setString(2, GSON.toJson(baselineConfiguration()));
				event.setObject(3, actorId);
				try (var result = event.executeQuery()) {
					assertTrue(result.next());
					legacyEventId = result.getLong(1);
				}
			}
			try (var stage = connection.prepareStatement("""
				INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type,state)
				VALUES (?,1,'INITIAL_CHARGE','ACTIVE') RETURNING id
				""")) {
				stage.setLong(1, legacyEventId);
				try (var result = stage.executeQuery()) {
					assertTrue(result.next());
					legacyStageId = result.getLong(1);
				}
			}
			try (var currentStage = connection.prepareStatement(
					"UPDATE bms.calibration_event SET current_stage_id=?,status='ACTIVE',started_at=now() WHERE id=?")) {
				currentStage.setLong(1, legacyStageId);
				currentStage.setLong(2, legacyEventId);
				assertEquals(1, currentStage.executeUpdate());
			}
		}

		var store = store();
		var healthy = store.start(store.create(new CreateCommand(healthyStringId, monthlyConfiguration(), null,
				operator(actorId))).event().id(), 0, operator(actorId));
		var workerEvents = store.activeWorkerEvents();
		assertFalse(workerEvents.stream().anyMatch(value -> value.eventId() == legacyEventId));
		var healthyWorker = workerEvents.stream().filter(value -> value.eventId() == healthy.event().id()).findFirst()
				.orElseThrow();
		assertTrue(store.persistWorkerEvaluation(healthyWorker, sampleWrite(healthyWorker, 1,
				System.currentTimeMillis(), System.currentTimeMillis(), CurrentQuality.FRESH, List.of(), "healthy")));
		assertWorkerState(healthy.event().id(), healthyWorker.stageId(), 1, 1, "ACTIVE", null, "healthy");
	}

	@Test
	public void stringSynchronizationCreatesCompletePostgresqlRecord() throws Exception {
		var repository = stringRepository();

		var created = repository.synchronize(coreDefinition("String 1", 1.85, 2.30, 2.45));
		var stored = repository.findByIndex(1).orElseThrow();

		assertEquals(created.id(), stored.id());
		assertEquals("modbus0", stored.bridgeId());
		assertEquals(1, stored.startTaUnitId());
		assertEquals(1.85, stored.cutoffVoltage(), 0.0001);
		assertEquals(2.30, stored.floatVoltage(), 0.0001);
		assertEquals(2.45, stored.boostVoltage(), 0.0001);
		assertEquals(1450, stored.ratedResistanceUohm(), 0.0001);
		assertEquals(1900, stored.highResistanceThreshold(), 0.0001);
	}

	@Test
	public void stringSynchronizationBackfillsShadowRowPreservesUuidAndUpdatesVoltages() throws Exception {
		var shadowId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection(); var insert = connection.prepareStatement("""
				INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count,brand,model,capacity_ah)
				VALUES (?,1,'str1','Legacy String',2,'Old','Old',100)
				""")) {
			insert.setObject(1, shadowId);
			insert.executeUpdate();
		}
		var repository = stringRepository();
		assertThrows(SQLException.class, () -> repository.findByIndex(1));

		var created = repository.synchronize(coreDefinition("String 1", 1.85, 2.30, 2.45));
		assertEquals(shadowId, created.id());
		assertEquals(1.85, repository.findByIndex(1).orElseThrow().cutoffVoltage(), 0.0001);

		var updated = repository.synchronize(coreDefinition("Updated String", 1.90, 2.35, 2.50));
		assertEquals(shadowId, updated.id());
		var stored = repository.findByIndex(1).orElseThrow();
		assertEquals("Updated String", stored.displayName());
		assertEquals(1.90, stored.cutoffVoltage(), 0.0001);
		assertEquals(2.35, stored.floatVoltage(), 0.0001);
		assertEquals(2.50, stored.boostVoltage(), 0.0001);
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement(
				"SELECT string_id,high_resistance FROM bms.string_threshold WHERE string_id=?")) {
			query.setObject(1, shadowId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(shadowId, result.getObject("string_id", UUID.class));
				assertEquals(1900, result.getDouble("high_resistance"), 0.0001);
			}
		}
	}

	@Test
	public void synchronizedShadowStringCanCreateMonthlyDraft() throws Exception {
		var actorId = UUID.randomUUID();
		var shadowId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, shadowId);
		}
		var repository = stringRepository();
		var runtime = coreDefinition("String 1", 1.85, 2.30, 2.45);
		var strings = new StringManagementService();
		setField(strings, "repository", repository);
		setField(strings, "lifecycle", new FixedManager(runtime, repository));
		var service = new CalibrationEventService();
		setField(service, "strings", strings);
		setField(service, "store", store());

		var draft = service.create(1, CalibrationEventType.MONTHLY_INSPECTION,
				DischargeCurrentDirection.NEGATIVE, -3.3, -5.0, null, null, null, null, operator(actorId));

		assertEquals("DRAFT", draft.event().status().name());
		assertEquals(1.85, draft.configuration().cutoffVoltagePerCell(), 0.0001);
		assertEquals(2.30, draft.configuration().floatVoltagePerCell(), 0.0001);
		assertEquals(2.45, draft.configuration().boostVoltagePerCell(), 0.0001);
		assertEquals(shadowId, draft.event().stringId());
	}

	@Test
	public void monthlyInspectionStartsAValidLegacyDraftWithoutDuplicatingStages() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long eventId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			eventId = insertDraft(connection, actorId, stringId);
			for (var stage : List.of("PREREQUISITES", "MONTHLY_INSPECTION", "COMPLETION")) {
				try (var insert = connection.prepareStatement("""
					INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type) VALUES (?,?,?)
					""")) {
					insert.setLong(1, eventId);
					insert.setInt(2, switch (stage) {
					case "PREREQUISITES" -> 0;
					case "MONTHLY_INSPECTION" -> 1;
					default -> 2;
					});
					insert.setString(3, stage);
					insert.executeUpdate();
				}
			}
		}
		var active = store().start(eventId, 0, operator(actorId));
		assertEquals(3, active.stages().size());
		assertEquals("ACTIVE", active.stages().get(0).state().name());
	}

	@Test
	public void monthlyInspectionRejectsInvalidLegacyStagesWithoutPartialMutation() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		long eventId;
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
			eventId = insertDraft(connection, actorId, stringId);
			insertStage(connection, eventId);
		}
		var store = store();
		assertThrows(IllegalArgumentException.class, () -> store.start(eventId, 0, operator(actorId)));
		var unchanged = store.get(eventId);
		assertEquals("DRAFT", unchanged.event().status().name());
		assertEquals(0, unchanged.event().eventVersion());
		assertNull(unchanged.event().currentStageId());
		assertEquals(1, unchanged.stages().size());
		assertTrue(store.history(eventId).transitions().isEmpty());
	}

	@Test
	public void concurrentMonthlyInspectionStartSerializesOnTheEventLock() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var draft = store.create(new CreateCommand(stringId, monthlyConfiguration(), null, actor));
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var calls = List.of(executor.submit(() -> concurrentStart(store, draft.event().id(), actor, ready, start)),
					executor.submit(() -> concurrentStart(store, draft.event().id(), actor, ready, start)));
			ready.await();
			start.countDown();
			var outcomes = calls.stream().map(call -> {
				try {
					return call.get();
				} catch (Exception e) {
					throw new AssertionError("Concurrent start failed unexpectedly", e);
				}
			}).toList();
			assertEquals(1, outcomes.stream().filter(outcome -> outcome == StartOutcome.STARTED).count());
			assertEquals(1, outcomes.stream().filter(outcome -> outcome == StartOutcome.CONFLICT).count());
		} finally {
			executor.shutdownNow();
		}
		var active = store.get(draft.event().id());
		assertEquals(1, active.event().eventVersion());
		assertEquals(3, active.stages().size());
		assertEquals(1, store.history(draft.event().id()).transitions().size());
	}

	@Test
	public void initialBaselineCreatesDraftAndStartsAllStagesAtomically() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var draft = store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor));
		assertEquals("DRAFT", draft.event().status().name());
		assertEquals(0, draft.event().eventVersion());
		assertNull(draft.event().currentStageId());
		assertTrue(draft.stages().isEmpty());
		assertTrue(draft.processes().isEmpty());

		var active = store.start(draft.event().id(), 0, actor);
		assertEquals("ACTIVE", active.event().status().name());
		assertEquals(1, active.event().eventVersion());
		assertEquals(List.of(CalibrationStageType.PREREQUISITES, CalibrationStageType.INITIAL_CHARGE,
				CalibrationStageType.PRE_DISCHARGE_FLOAT, CalibrationStageType.WAITING_FOR_LOAD,
				CalibrationStageType.DISCHARGE, CalibrationStageType.LOAD_DISCONNECT,
				CalibrationStageType.RECHARGE, CalibrationStageType.POST_RECHARGE_FLOAT,
				CalibrationStageType.BASELINE_CAPTURE, CalibrationStageType.COMPLETION),
				active.stages().stream().map(stage -> stage.type()).toList());
		assertEquals(List.of("ACTIVE", "NOT_STARTED", "NOT_STARTED", "NOT_STARTED", "NOT_STARTED",
				"NOT_STARTED", "NOT_STARTED", "NOT_STARTED", "NOT_STARTED", "NOT_STARTED"),
				active.stages().stream().map(stage -> stage.state().name()).toList());
		assertEquals(active.stages().get(0).id(), active.event().currentStageId().longValue());
		// INITIAL_CHARGE has 2 processes, PRE_DISCHARGE_FLOAT/DISCHARGE/RECHARGE/
		// POST_RECHARGE_FLOAT have 1 each; checklist-only stages have none.
		assertEquals(7, active.processes().size());
		assertEquals(1, store.history(draft.event().id()).transitions().size());
		assertThrows(CalibrationConflictException.class, () -> store.start(draft.event().id(), 0, actor));
		assertEquals(10, store.get(draft.event().id()).stages().size());
		assertEquals(7, store.get(draft.event().id()).processes().size());
	}

	@Test
	public void dischargeSafetyFloorIsVersionedAuditedAndImmutableAfterProcessStart() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var waitingForLoad = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.WAITING_FOR_LOAD).findFirst().orElseThrow();
		var discharge = active.processes().stream()
				.filter(value -> "CONTROLLED_DISCHARGE".equals(value.type())).findFirst().orElseThrow();
		activateStage(active.event().id(), waitingForLoad.id());

		var configured = store.configureDischargeSafetyFloor(new ConfigureDischargeSafetyFloorCommand(
				active.event().id(), waitingForLoad.id(), active.event().eventVersion(), 1.65), actor);
		assertEquals(2, configured.event().eventVersion());
		assertEquals(1.65, configured.configuration().safetyFloorVoltagePerCell(), 0.000001);
		assertEquals(1.65, configured.processes().stream().filter(value -> value.id() == discharge.id())
				.findFirst().orElseThrow().properties().getAsJsonObject()
				.get("safetyFloorVoltagePerCell").getAsDouble(), 0.000001);
		var transition = store.history(active.event().id()).transitions().get(1);
		assertEquals("CONFIGURE_DISCHARGE_SAFETY_FLOOR", transition.action());
		assertEquals(1.65, transition.evidence().getAsJsonObject()
				.get("safetyFloorVoltagePerCell").getAsDouble(), 0.000001);

		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now() WHERE id=?
			""")) {
			update.setLong(1, discharge.id());
			assertEquals(1, update.executeUpdate());
		}
		assertThrows(IllegalArgumentException.class, () -> store.configureDischargeSafetyFloor(
				new ConfigureDischargeSafetyFloorCommand(active.event().id(), waitingForLoad.id(), 2, 1.60), actor));
		assertEquals(1.65, store.get(active.event().id()).configuration().safetyFloorVoltagePerCell(), 0.000001);
	}

	@Test
	public void dischargeMonitorPolicyIsVersionedAuditedAndImmutableAfterProcessStart() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var waitingForLoad = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.WAITING_FOR_LOAD).findFirst().orElseThrow();
		var discharge = active.processes().stream()
				.filter(value -> "CONTROLLED_DISCHARGE".equals(value.type())).findFirst().orElseThrow();
		activateStage(active.event().id(), waitingForLoad.id());

		var configured = store.configureDischargeMonitor(new ConfigureDischargeMonitorCommand(
				active.event().id(), waitingForLoad.id(), active.event().eventVersion(), 1.65, 5), actor);
		assertEquals(2, configured.event().eventVersion());
		var process = configured.processes().stream().filter(value -> value.id() == discharge.id())
				.findFirst().orElseThrow();
		assertEquals(1.65, process.properties().getAsJsonObject()
				.get("safetyFloorVoltagePerCell").getAsDouble(), 0.000001);
		assertEquals(5, process.properties().getAsJsonObject()
				.get("maxWrongCurrentDirectionSamples").getAsInt());
		var transition = store.history(active.event().id()).transitions().get(1);
		assertEquals("CONFIGURE_DISCHARGE_MONITOR", transition.action());
		assertEquals(5, transition.evidence().getAsJsonObject()
				.get("maxWrongCurrentDirectionSamples").getAsInt());

		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process SET state='ACTIVE',started_at=now() WHERE id=?
			""")) {
			update.setLong(1, discharge.id());
			assertEquals(1, update.executeUpdate());
		}
		assertThrows(IllegalArgumentException.class, () -> store.configureDischargeMonitor(
				new ConfigureDischargeMonitorCommand(active.event().id(), waitingForLoad.id(), 2, 1.60, 4), actor));
		assertThrows(IllegalArgumentException.class, () -> store.configureDischargeMonitor(
				new ConfigureDischargeMonitorCommand(active.event().id(), waitingForLoad.id(), 2, 1.60, 0), actor));
	}

	@Test
	public void initialBoostRequiresEvidenceAndRejectsManualAutomaticCompletion() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var stage = active.stages().stream().filter(value -> value.type() == CalibrationStageType.INITIAL_CHARGE)
				.findFirst().orElseThrow();
		var boost = active.processes().stream().filter(value -> value.calibrationStageId() == stage.id()
				&& "BOOST_CHARGE_TO_VBOOST".equals(value.type())).findFirst().orElseThrow();
		var floatProcess = active.processes().stream().filter(value -> value.calibrationStageId() == stage.id()
				&& "FLOAT_SETTLEMENT_TO_VFLOAT".equals(value.type())).findFirst().orElseThrow();
		activateStage(active.event().id(), stage.id());

		assertThrows(IllegalArgumentException.class, () -> store.startProcess(
				new io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand(
						active.event().id(), stage.id(), boost.id(), 1), actor));
		store.appendEvidence(new ManualEvidenceCommand(active.event().id(), stage.id(), 1,
				"CHARGE_SETPOINTS_REVIEWED", "PASS", null, null, null, null, null, null, null, null, null), actor);
		assertThrows(IllegalArgumentException.class, () -> store.startProcess(
				new io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand(
						active.event().id(), stage.id(), floatProcess.id(), 2), actor));
		var started = store.startProcess(
				new io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand(
						active.event().id(), stage.id(), boost.id(), 2), actor);
		assertEquals(3, started.event().eventVersion());
		assertEquals("ACTIVE", started.processes().stream().filter(value -> value.id() == boost.id()).findFirst()
				.orElseThrow().state().name());
		assertThrows(IllegalArgumentException.class, () -> store.completeProcess(
				new io.openems.edge.bms.service.calibration.model.CalibrationEventModels.CompleteProcessCommand(
						active.event().id(), stage.id(), boost.id(), 3, "manual bypass"), actor));
		assertEquals(3, store.get(active.event().id()).event().eventVersion());
	}

	@Test
	public void initialBaselineDemoTimeScaleIsVersionedPersistedAuditedAndPrerequisitesOnly() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var prerequisites = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.PREREQUISITES).findFirst().orElseThrow();
		var configured = store.configureDemoTimeScale(new ConfigureDemoTimeScaleCommand(active.event().id(),
				prerequisites.id(), active.event().eventVersion(), 60), actor);
		assertEquals(2, configured.event().eventVersion());
		assertEquals(60, configured.stages().get(0).properties().getAsJsonObject()
				.get("demoTimeScaleFactor").getAsInt());
		assertEquals(2, configured.processes().stream()
				.filter(value -> "CONTROLLED_DISCHARGE".equals(value.type())).findFirst().orElseThrow()
				.properties().getAsJsonObject().get("dischargeSamplingIntervalSeconds").getAsInt());
		var transition = store.history(active.event().id()).transitions().get(1);
		assertEquals("CONFIGURE_DEMO_TIME_SCALE", transition.action());
		assertEquals(60, transition.evidence().getAsJsonObject().get("demoTimeScaleFactor").getAsInt());
		assertEquals(2, transition.evidence().getAsJsonObject()
				.get("dischargeSamplingIntervalSeconds").getAsInt());
		assertThrows(CalibrationConflictException.class, () -> store.configureDemoTimeScale(
				new ConfigureDemoTimeScaleCommand(active.event().id(), prerequisites.id(), 1, 1), actor));
		assertThrows(IllegalArgumentException.class, () -> store.configureDemoTimeScale(
				new ConfigureDemoTimeScaleCommand(active.event().id(), prerequisites.id(), 2, 2), actor));
		assertEquals(2, store.get(active.event().id()).event().eventVersion());
		activateStage(active.event().id(), active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.INITIAL_CHARGE).findFirst().orElseThrow().id());
		long currentVersion = store.get(active.event().id()).event().eventVersion();
		assertThrows(CalibrationConflictException.class, () -> store.configureDemoTimeScale(
				new ConfigureDemoTimeScaleCommand(active.event().id(), prerequisites.id(), currentVersion, 1), actor));
	}

	@Test
	public void initialChargeWorkerCompletesFloatButKeepsStageActiveForOperatorReviews() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		assertTrue(active.processes().stream().allMatch(value -> value.runtime() == null));
		assertTrue(active.processes().stream().allMatch(value -> value.dischargeRuntime() == null));
		var initialCharge = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.INITIAL_CHARGE).findFirst().orElseThrow();
		var boost = active.processes().stream().filter(value -> value.calibrationStageId() == initialCharge.id()
				&& "BOOST_CHARGE_TO_VBOOST".equals(value.type())).findFirst().orElseThrow();
		var initialFloat = active.processes().stream().filter(value -> value.calibrationStageId() == initialCharge.id()
				&& "FLOAT_SETTLEMENT_TO_VFLOAT".equals(value.type())).findFirst().orElseThrow();
		activateStage(active.event().id(), initialCharge.id());

		store.appendEvidence(new ManualEvidenceCommand(active.event().id(), initialCharge.id(), 1,
				"CHARGE_SETPOINTS_REVIEWED", "PASS", null, null, null, null, null, null, null, null, null), actor);
		store.startProcess(new io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand(
				active.event().id(), initialCharge.id(), boost.id(), 2), actor);

		var boostWorker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId().equals(boost.id()))
				.findFirst().orElseThrow();
		var boostWrite = sampleWrite(boostWorker, 201, System.currentTimeMillis(), System.currentTimeMillis(),
				CurrentQuality.FRESH, List.of(), "initial-boost");
		assertTrue(store.persistWorkerEvaluation(boostWorker, new SampleWrite(boostWrite.measurement(),
				new CalibrationStageEvaluation<>(Map.of("marker", "boost"), List.of(), List.of(),
						List.of(PersistenceReason.FINAL_SNAPSHOT), Recommendation.READY_TO_COMPLETE),
				"VALID", null, System.currentTimeMillis())));
		var afterBoost = store.get(active.event().id());
		assertEquals("COMPLETED", afterBoost.processes().stream().filter(value -> value.id() == boost.id())
				.findFirst().orElseThrow().state().name());
		assertEquals("ACTIVE", afterBoost.processes().stream().filter(value -> value.id() == initialFloat.id())
				.findFirst().orElseThrow().state().name());
		assertEquals(initialCharge.id(), afterBoost.event().currentStageId().longValue());
		var changedFloatPolicy = store.configureActiveFloatCurrentRange(new ConfigureActiveFloatCurrentRangeCommand(
				active.event().id(), initialCharge.id(), initialFloat.id(), initialFloat.attemptNumber(),
				afterBoost.event().eventVersion(), 0.01, 0.08, "Observed charger float current"), actor);
		var appliedFloat = changedFloatPolicy.processes().stream().filter(value -> value.id() == initialFloat.id())
				.findFirst().orElseThrow().configuration().getAsJsonObject();
		assertEquals(2, appliedFloat.get("policyRevision").getAsInt());
		assertEquals(0.01, appliedFloat.get("floatCurrentMinA").getAsDouble(), 0.000001);
		assertEquals(0.08, appliedFloat.get("floatCurrentMaxA").getAsDouble(), 0.000001);
		var policyTransition = store.history(active.event().id()).transitions().stream()
				.filter(value -> "UPDATE_ACTIVE_FLOAT_POLICY".equals(value.action())).findFirst().orElseThrow();
		assertEquals(2, policyTransition.evidence().getAsJsonObject().get("policyRevision").getAsInt());
		assertTrue(policyTransition.evidence().getAsJsonObject().get("stableWindowReset").getAsBoolean());

		var floatWorker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId().equals(initialFloat.id()))
				.findFirst().orElseThrow();
		var floatWrite = sampleWrite(floatWorker, 202, System.currentTimeMillis(), System.currentTimeMillis(),
				CurrentQuality.FRESH, List.of(), "initial-float");
		assertTrue(store.persistWorkerEvaluation(floatWorker, new SampleWrite(floatWrite.measurement(),
				new CalibrationStageEvaluation<>(Map.of("marker", "float"), List.of(), List.of(),
						List.of(PersistenceReason.FINAL_SNAPSHOT), Recommendation.READY_TO_COMPLETE),
				"VALID", null, System.currentTimeMillis())));
		var afterFloat = store.get(active.event().id());
		assertEquals("ACTIVE", afterFloat.stages().stream().filter(value -> value.id() == initialCharge.id())
				.findFirst().orElseThrow().state().name());
		assertEquals("COMPLETED", afterFloat.processes().stream().filter(value -> value.id() == initialFloat.id())
				.findFirst().orElseThrow().state().name());
		assertEquals(initialCharge.id(), afterFloat.event().currentStageId().longValue());
		assertEquals(4, afterFloat.event().eventVersion());
		var boostDetail = afterFloat.processes().stream().filter(value -> value.id() == boost.id()).findFirst()
				.orElseThrow();
		assertTrue(boostDetail.configuration().isJsonObject());
		assertFalse(boostDetail.configuration().getAsJsonObject().has("latestMeasurement"));
		assertEquals(201, boostDetail.runtime().getAsJsonObject().get("snapshotSequence").getAsLong());
		assertNull(boostDetail.dischargeRuntime());
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT count(*),bool_and(NOT jsonb_exists_any(process.properties, ARRAY
				['checkpoint','latestMeasurement','blockers','recommendation']::text[]))
			FROM bms.calibration_process process
			JOIN bms.calibration_process_runtime runtime
				ON runtime.calibration_process_id=process.id
				AND runtime.attempt_number=process.attempt_number
				WHERE process.id IN (?,?) AND jsonb_exists(runtime.latest_measurement,'cells')
			""")) {
			query.setLong(1, boost.id());
			query.setLong(2, initialFloat.id());
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(2, result.getInt(1));
				assertTrue(result.getBoolean(2));
			}
		}

		var boostReviewed = store.appendEvidence(new ManualEvidenceCommand(active.event().id(), initialCharge.id(), 4,
				"BOOST_TARGET_REVIEWED", "PASS", null, null, null, null, null, null, null, null, null), actor);
		assertEquals(initialCharge.id(), boostReviewed.event().currentStageId().longValue());
		var floatReviewed = store.appendEvidence(new ManualEvidenceCommand(active.event().id(), initialCharge.id(), 5,
				"FLOAT_MODE_REVIEWED", "PASS", null, null, null, null, null, null, null, null, null), actor);
		assertEquals(initialCharge.id(), floatReviewed.event().currentStageId().longValue());
	}

	@Test
	public void cooldownWorkerCompletesDespiteStaleChargeTargetProperties() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var cooldown = active.stages().stream()
				.filter(value -> value.type() == CalibrationStageType.PRE_DISCHARGE_FLOAT).findFirst().orElseThrow();
		var cooldownProcess = active.processes().stream().filter(value -> value.calibrationStageId() == cooldown.id()
				&& "COOL_DOWN_TO_AMBIENT".equals(value.type())).findFirst().orElseThrow();
		activateStage(active.event().id(), cooldown.id());

		store.appendEvidence(new ManualEvidenceCommand(active.event().id(), cooldown.id(), 1,
				"CHARGER_MCB_OPEN_FOR_COOLDOWN", "PASS", null, null, null, null, null, null, null, null, null), actor);
		store.startProcess(new io.openems.edge.bms.service.calibration.model.CalibrationEventModels.StartProcessCommand(
				active.event().id(), cooldown.id(), cooldownProcess.id(), 2), actor);
		try (var connection = this.dataSource.getConnection(); var update = connection.prepareStatement("""
			UPDATE bms.calibration_process
			SET properties=properties || '{"floatTargetVoltageV":253.0,"boostTargetVoltageV":269.5}'::jsonb
			WHERE id=?
			""")) {
			update.setLong(1, cooldownProcess.id());
			assertEquals(1, update.executeUpdate());
		}

		var worker = store.activeWorkerEvents().stream()
				.filter(value -> value.eventId() == active.event().id() && value.processId().equals(cooldownProcess.id()))
				.findFirst().orElseThrow();
		var write = sampleWrite(worker, 301, System.currentTimeMillis(), System.currentTimeMillis(),
				CurrentQuality.FRESH, List.of(), "cooldown");
		var finalWrite = new SampleWrite(write.measurement(), new CalibrationStageEvaluation<>(Map.of("marker", "cooldown"),
				List.of(), List.of(), List.of(PersistenceReason.FINAL_SNAPSHOT), Recommendation.READY_TO_COMPLETE),
				"VALID", null, System.currentTimeMillis());
		assertTrue(store.persistWorkerEvaluation(worker, finalWrite));

		var after = store.get(active.event().id());
		assertEquals("COMPLETED", after.processes().stream().filter(value -> value.id() == cooldownProcess.id())
				.findFirst().orElseThrow().state().name());
	}

	private void activateStage(long eventId, long stageId) throws SQLException {
		try (var connection = this.dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var clear = connection.prepareStatement(
					"UPDATE bms.calibration_stage SET state='NOT_STARTED' WHERE calibration_event_id=? AND state='ACTIVE'")) {
				clear.setLong(1, eventId);
				clear.executeUpdate();
			}
			try (var activate = connection.prepareStatement(
					"UPDATE bms.calibration_stage SET state='ACTIVE',started_at=now() WHERE id=?")) {
				activate.setLong(1, stageId);
				activate.executeUpdate();
			}
			try (var event = connection.prepareStatement(
					"UPDATE bms.calibration_event SET current_stage_id=? WHERE id=?")) {
				event.setLong(1, stageId);
				event.setLong(2, eventId);
				event.executeUpdate();
			}
			connection.commit();
		}
	}

	@Test
	public void concurrentInitialBaselineStartSerializesOnTheEventLock() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var draft = store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor));
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var calls = List.of(executor.submit(() -> concurrentStart(store, draft.event().id(), actor, ready, start)),
					executor.submit(() -> concurrentStart(store, draft.event().id(), actor, ready, start)));
			ready.await();
			start.countDown();
			var outcomes = calls.stream().map(call -> {
				try {
					return call.get();
				} catch (Exception e) {
					throw new AssertionError("Concurrent start failed unexpectedly", e);
				}
			}).toList();
			assertEquals(1, outcomes.stream().filter(outcome -> outcome == StartOutcome.STARTED).count());
			assertEquals(1, outcomes.stream().filter(outcome -> outcome == StartOutcome.CONFLICT).count());
		} finally {
			executor.shutdownNow();
		}
		var active = store.get(draft.event().id());
		assertEquals(1, active.event().eventVersion());
		assertEquals(10, active.stages().size());
		assertEquals(7, active.processes().size());
		assertEquals(1, store.history(draft.event().id()).transitions().size());
	}

	@Test
	public void baselineVocSequenceLinksExactlyOneCurrentAttemptFinalSnapshot() throws Exception {
		var actorId = UUID.randomUUID();
		var stringId = UUID.randomUUID();
		try (var connection = this.dataSource.getConnection()) {
			seed(connection, actorId, stringId);
		}
		var store = store();
		var actor = operator(actorId);
		var active = store.start(store.create(new CreateCommand(stringId, baselineConfiguration(), null, actor))
				.event().id(), 0, actor);
		var baseline = active.stages().stream().filter(stage -> stage.type() == CalibrationStageType.BASELINE_CAPTURE)
				.findFirst().orElseThrow();
		try (var connection = this.dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var clear = connection.prepareStatement("""
				UPDATE bms.calibration_stage SET state='NOT_STARTED' WHERE calibration_event_id=? AND state='ACTIVE'
				""")) {
				clear.setLong(1, active.event().id());
				clear.executeUpdate();
			}
			try (var activate = connection.prepareStatement(
					"UPDATE bms.calibration_stage SET state='ACTIVE',started_at=now() WHERE id=?")) {
				activate.setLong(1, baseline.id());
				activate.executeUpdate();
			}
			try (var event = connection.prepareStatement(
					"UPDATE bms.calibration_event SET current_stage_id=? WHERE id=?")) {
				event.setLong(1, baseline.id());
				event.setLong(2, active.event().id());
				event.executeUpdate();
			}
			connection.commit();
		}

		assertThrows(IllegalArgumentException.class, () -> store.appendEvidence(new ManualEvidenceCommand(
				active.event().id(), baseline.id(), 1, "RIPPLE_AFTER_LOAD", "RECORDED", 0.08, null, "V",
				null, null, null, null, null, null), actor));
		store.appendEvidence(evidence(active.event().id(), baseline.id(), 1, "LOAD_CONNECTED_CONFIRMATION", null,
				null, null), actor);
		assertThrows(IllegalArgumentException.class, () -> store.appendEvidence(evidence(active.event().id(),
				baseline.id(), 2, "RIPPLE_AFTER_LOAD", null, "0.08", null), actor));
		store.appendEvidence(evidence(active.event().id(), baseline.id(), 2, "RIPPLE_AFTER_LOAD", 0.08, "V", null), actor);
		store.appendEvidence(evidence(active.event().id(), baseline.id(), 3, "CHARGER_MCB_OPEN_CONFIRMATION", null,
				null, null), actor);

		var worker = store.activeWorkerEvents().stream().filter(value -> value.eventId() == active.event().id())
				.findFirst().orElseThrow();
		assertEquals("VOC_BASELINE_CAPTURE", store.get(active.event().id()).processes().stream()
				.filter(value -> value.id() == worker.processId()).findFirst().orElseThrow().type());
		var now = System.currentTimeMillis();
		var ordinary = sampleWrite(worker, 101, now, now, CurrentQuality.FRESH, List.of(), "voc");
		var finalWrite = new SampleWrite(ordinary.measurement(), new CalibrationStageEvaluation<>(Map.of("marker", "voc"),
				List.of(), List.of(), List.of(PersistenceReason.FINAL_SNAPSHOT), Recommendation.READY_TO_COMPLETE),
				"VALID", null, now);
		assertTrue(store.persistWorkerEvaluation(worker, finalWrite));
		var samples = store.samples(active.event().id());
		assertEquals(1, samples.stream().filter(sample -> "FINAL_SNAPSHOT".equals(sample.persistenceReason())
				&& sample.calibrationStageId() == baseline.id()).count());
		var sample = samples.get(samples.size() - 1);
		assertEquals(worker.processId(), sample.calibrationProcessId());
		assertEquals(worker.processAttemptNumber(), sample.processAttemptNumber());
		assertEquals("COMPLETED", store.get(active.event().id()).processes().stream()
				.filter(value -> value.id() == worker.processId()).findFirst().orElseThrow().state().name());

		store.appendEvidence(evidence(active.event().id(), baseline.id(), 4, "VOC_BASELINE_CONFIRMATION", null,
				null, sample.id()), actor);
		assertEquals(sample.id(), store.history(active.event().id()).evidence().stream()
				.filter(value -> "VOC_BASELINE_CONFIRMATION".equals(value.evidenceType())).findFirst().orElseThrow()
				.calibrationSampleId().longValue());

		var completion = store.executeAction(new ExecuteActionCommand(active.event().id(), baseline.id(),
				ActionCode.COMPLETE_STAGE, 5, null, List.of()), actor,
				detail -> List.of(new AllowedAction(ActionCode.COMPLETE_STAGE, "Complete stage", ActionIntent.PRIMARY,
						true, null, false, false, List.of())));
		var redone = store.redoStage(new RedoStageCommand(active.event().id(), baseline.id(), 6, "repeat capture"), actor);
		var replayProcess = redone.processes().stream().filter(value -> value.id() == worker.processId()).findFirst()
				.orElseThrow();
		assertEquals(1, replayProcess.attemptNumber());
		assertEquals("NOT_STARTED", replayProcess.state().name());
		assertFalse(replayProcess.properties().getAsJsonObject().has("latestMeasurement"));
		assertEquals(CalibrationStageType.COMPLETION, completion.stages().stream()
				.filter(stage -> stage.id() == completion.event().currentStageId()).findFirst().orElseThrow().type());

		store.appendEvidence(evidence(active.event().id(), baseline.id(), 7, "LOAD_CONNECTED_CONFIRMATION", null,
				null, null), actor);
		store.appendEvidence(evidence(active.event().id(), baseline.id(), 8, "RIPPLE_AFTER_LOAD", 0.09, "V", null), actor);
		store.appendEvidence(evidence(active.event().id(), baseline.id(), 9, "CHARGER_MCB_OPEN_CONFIRMATION", null,
				null, null), actor);
		assertThrows(IllegalArgumentException.class, () -> store.appendEvidence(evidence(active.event().id(),
				baseline.id(), 10, "VOC_BASELINE_CONFIRMATION", null, null, sample.id()), actor));
	}

	private static ManualEvidenceCommand evidence(long eventId, long stageId, long version, String type,
			Double numericValue, String unit, Long sampleId) {
		return new ManualEvidenceCommand(eventId, stageId, version, type, "RECORDED", numericValue, null, unit,
				null, null, null, null, null, null, sampleId);
	}

	private static StartOutcome concurrentStart(CalibrationEventStore store, long eventId,
			AuthenticatedPrincipal actor, CountDownLatch ready, CountDownLatch start)
			throws SQLException, InterruptedException {
		ready.countDown();
		start.await();
		try {
			store.start(eventId, 0, actor);
			return StartOutcome.STARTED;
		} catch (CalibrationConflictException e) {
			return StartOutcome.CONFLICT;
		}
	}

	private enum StartOutcome {
		STARTED,
		CONFLICT
	}

	private static SampleWrite sampleWrite(WorkerEvent worker, long sequence, long capturedAt,
			long currentMeasuredAt, CurrentQuality currentQuality, List<CalibrationBlocker> blockers, String marker) {
		return sampleWrite(worker, UUID.randomUUID(), sequence, capturedAt, currentMeasuredAt, currentQuality,
				blockers, marker);
	}

	private static SampleWrite sampleWrite(WorkerEvent worker, UUID generation, long sequence, long capturedAt,
			long currentMeasuredAt, CurrentQuality currentQuality, List<CalibrationBlocker> blockers, String marker) {
		var configuration = worker.configuration();
		double rawCurrent = 0.05;
		var cells = Map.of(
				1, new CalibrationMeasurement.Cell(1, 2.30, 25.0, 1_000, 90.0, 95.0, 1_000.0, null),
				2, new CalibrationMeasurement.Cell(2, 2.31, 25.0, 1_001, 89.0, 94.0, 1_001.0, null));
		var measurement = new CalibrationMeasurement(generation, sequence, capturedAt,
				configuration.cellCount(), 25.0, rawCurrent,
				configuration.currentDirection().toDomainCurrent(rawCurrent), currentMeasuredAt, currentQuality,
				currentQuality == CurrentQuality.FRESH ? null : "CURRENT_VALUE_RETAINED",
				configuration.currentDirection(), configuration.mappingVersion(), 90.0, 95.0, 4.61, 2.30, 2.305, 25.0,
				1_000.5, cells, null);
		var evaluation = new CalibrationStageEvaluation<>(Map.of("marker", marker), blockers, List.of(),
				List.of(blockers.isEmpty() ? PersistenceReason.PERIODIC_SAMPLE : PersistenceReason.QUALITY_CHANGE),
				blockers.isEmpty() ? Recommendation.READY_TO_COMPLETE : Recommendation.BLOCKED);
		var blockerReason = blockers.isEmpty() ? null : blockers.get(0).name();
		var quality = blockers.contains(CalibrationBlocker.CURRENT_STALE) ? "CURRENT_UNAVAILABLE"
				: blockers.isEmpty() ? "VALID" : "INVALID";
		return new SampleWrite(measurement, evaluation, quality, blockerReason, capturedAt);
	}

	private static CalibrationMeasurement dischargeMeasurement(WorkerEvent worker, UUID generation, long sequence,
			long capturedAt) {
		return dischargeMeasurement(worker, generation, sequence, capturedAt, 2.30, 2.31);
	}

	private static CalibrationMeasurement dischargeMeasurement(WorkerEvent worker, UUID generation, long sequence,
			long capturedAt, double cellOneVoltage, double cellTwoVoltage) {
		return dischargeMeasurement(worker, generation, sequence, capturedAt, 10.0, cellOneVoltage, cellTwoVoltage);
	}

	private static CalibrationMeasurement dischargeMeasurement(WorkerEvent worker, UUID generation, long sequence,
			long capturedAt, double domainCurrentMagnitude, double cellOneVoltage, double cellTwoVoltage) {
		var configuration = worker.configuration();
		double rawCurrent = configuration.currentDirection() == DischargeCurrentDirection.NEGATIVE
				? -domainCurrentMagnitude : domainCurrentMagnitude;
		var cells = Map.of(
				1, new CalibrationMeasurement.Cell(1, cellOneVoltage, 25.0, 1_000, 90.0, 95.0, 1_000.0, null),
				2, new CalibrationMeasurement.Cell(2, cellTwoVoltage, 25.0, 1_001, 89.0, 94.0, 1_001.0, null));
		double stringVoltage = cellOneVoltage + cellTwoVoltage;
		return new CalibrationMeasurement(generation, sequence, capturedAt, configuration.cellCount(), 25.0,
				rawCurrent, configuration.currentDirection().toDomainCurrent(rawCurrent), capturedAt,
				CurrentQuality.FRESH, null, configuration.currentDirection(), configuration.mappingVersion(), 90.0, 95.0,
				stringVoltage, stringVoltage / 2.0, stringVoltage / 2.0, 25.0, 1_000.5, cells, null);
	}

	private void assertWorkerState(long eventId, long stageId, int sampleCount, int measurementRevision,
			String stageState, String blockerCode, String marker) throws SQLException {
		try (var connection = this.dataSource.getConnection(); var query = connection.prepareStatement("""
			SELECT (SELECT count(*) FROM bms.calibration_sample WHERE calibration_stage_id=?),
				e.measurement_revision,s.state,s.blocker_code,s.properties->>'marker'
			FROM bms.calibration_event e JOIN bms.calibration_stage s ON s.id=e.current_stage_id
			WHERE e.id=?
			""")) {
			query.setLong(1, stageId);
			query.setLong(2, eventId);
			try (var result = query.executeQuery()) {
				assertTrue(result.next());
				assertEquals(sampleCount, result.getInt(1));
				assertEquals(measurementRevision, result.getInt(2));
				assertEquals(stageState, result.getString(3));
				assertEquals(blockerCode, result.getString(4));
				assertEquals(marker, result.getString(5));
			}
		}
	}

	private CalibrationEventStore store() throws Exception {
		var store = new CalibrationEventStore();
		var databaseField = CalibrationEventStore.class.getDeclaredField("database");
		databaseField.setAccessible(true);
		databaseField.set(store, new TestDatabase(this.dataSource));
		return store;
	}

	private PostgresStringRepository stringRepository() throws Exception {
		var repository = new PostgresStringRepository();
		setField(repository, "database", new TestDatabase(this.dataSource));
		return repository;
	}

	private static io.openems.edge.bms.service.strings.model.BmsStringModels.Definition coreDefinition(String name,
			double cutoff, double floatVoltage, double boostVoltage) {
		return new io.openems.edge.bms.service.strings.model.BmsStringModels.Definition(1, name, 2, "Brand", "Model", 200,
				cutoff, floatVoltage, boostVoltage, 1450, 1900, 50, 1.80, 2.50, "modbus0", 1);
	}

	private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static AuthenticatedPrincipal operator(UUID actorId) {
		return new AuthenticatedPrincipal(actorId, "operator", "Operator Name", Set.of(PlatformRole.BMS_OPERATOR));
	}

	private static CalibrationEventConfiguration monthlyConfiguration() {
		return new CalibrationEventConfiguration(CalibrationEventType.MONTHLY_INSPECTION, "str1", 2, 200, 200,
				null, 1.8, 2.25, 2.4, DischargeCurrentDirection.NEGATIVE, 1, -3.3, -5.0, 0.2, 300, 60,
				null, null, null, 0.100, 0.200, 0.300, "SITE_POLICY_V1");
	}

	private static CalibrationEventConfiguration baselineConfiguration() {
		return new CalibrationEventConfiguration(CalibrationEventType.INITIAL_BASELINE, "str1", 2, 200, 200,
				null, 1.8, 2.25, 2.4, DischargeCurrentDirection.NEGATIVE, 1, -3.3, -5.0, 0.2, 300, 60,
				DischargeMethod.C10, DischargeEndpointMode.FULL_CUTOFF, null, 0.100, 0.200, 0.300,
				"SITE_POLICY_V1");
	}

	private static void seed(Connection connection, UUID actorId, UUID stringId) throws SQLException {
		try (var actor = connection.prepareStatement("""
			INSERT INTO bms.auth_user(id,username,display_name,password_hash,salt) VALUES (?,?,'Operator Name','x'::bytea,'y'::bytea)
			"""); var string = connection.prepareStatement("""
			INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count,capacity_ah)
			VALUES (?,1,'str1','String 1',2,200)
			""")) {
			actor.setObject(1, actorId);
			actor.setString(2, "operator-" + actorId);
			actor.executeUpdate();
			string.setObject(1, stringId);
			string.executeUpdate();
		}
	}

	private static long insertDraft(Connection connection, UUID actorId, UUID stringId) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_event
				(string_id,event_type,configuration_snapshot,created_by,created_by_name)
			VALUES (?,'MONTHLY_INSPECTION',?::jsonb,?,'Operator Name') RETURNING id
			""")) {
			insert.setObject(1, stringId);
			// Store the same complete immutable snapshot as production create(). An empty
			// JSON object cannot hydrate the record's required primitive/enum fields and
			// hides the persistence behavior behind a Gson constructor NPE.
			insert.setString(2, GSON.toJson(monthlyConfiguration()));
			insert.setObject(3, actorId);
			try (var result = insert.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private static long insertStage(Connection connection, long eventId) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_stage(calibration_event_id,stage_order,stage_type)
			VALUES (?,0,'PREREQUISITES') RETURNING id
			""")) {
			insert.setLong(1, eventId);
			try (var result = insert.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private static long insertProcess(Connection connection, long eventId, long stageId, String processType,
			String properties) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_process
				(calibration_event_id,calibration_stage_id,process_order,process_type,sampling_policy,properties)
			VALUES (?,?,0,?,'FINAL_SNAPSHOT',?::jsonb) RETURNING id
			""")) {
			insert.setLong(1, eventId);
			insert.setLong(2, stageId);
			insert.setString(3, processType);
			insert.setString(4, properties);
			try (var result = insert.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private static void insertWrongEventSample(Connection connection, UUID actorId, long stageId) throws SQLException {
		var otherString = UUID.randomUUID();
		try (var string = connection.prepareStatement("""
			INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count,capacity_ah)
			VALUES (?,2,'str2','String 2',2,200)
			""")) {
			string.setObject(1, otherString);
			string.executeUpdate();
		}
		var otherEvent = insertDraft(connection, actorId, otherString);
		insertSample(connection, otherEvent, stageId, UUID.randomUUID(), 1);
	}

	private static void insertSample(Connection connection, long eventId, long stageId, UUID generation,
			long sequence) throws SQLException {
		try (var insert = connection.prepareStatement("""
			INSERT INTO bms.calibration_sample
				(calibration_event_id,calibration_stage_id,snapshot_generation,snapshot_sequence,measurement_time,
				 persistence_reason,quality)
			VALUES (?,?,?,?,now(),'PERIODIC_SAMPLE','VALID')
			""")) {
			insert.setLong(1, eventId);
			insert.setLong(2, stageId);
			insert.setObject(3, generation);
			insert.setLong(4, sequence);
			insert.executeUpdate();
		}
	}

	private static void updateTransition(Connection connection, long transitionId) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE bms.calibration_transition SET reason='changed' WHERE id=?")) {
			update.setLong(1, transitionId);
			update.executeUpdate();
		}
	}

	private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
		try (var query = connection.prepareStatement("""
			SELECT EXISTS (SELECT 1 FROM information_schema.columns
				WHERE table_schema='bms' AND table_name=? AND column_name=?)
			""")) {
			query.setString(1, table);
			query.setString(2, column);
			try (var result = query.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	private static void dropColumns(java.sql.Statement statement, String table, String... columns)
			throws SQLException {
		for (var column : columns) {
			statement.execute("ALTER TABLE bms." + table + " DROP COLUMN IF EXISTS " + column);
		}
	}

	private static void assertColumns(Connection connection, String table, String... columns) throws SQLException {
		for (var column : columns) {
			assertTrue(table + "." + column + " must exist after migration", hasColumn(connection, table, column));
		}
	}

	private static void updateClosedEvent(Connection connection, long eventId) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE bms.calibration_event SET missing_evidence='[\"changed\"]'::jsonb WHERE id=?")) {
			update.setLong(1, eventId);
			update.executeUpdate();
		}
	}

	private static void deleteClosedEvent(Connection connection, long eventId) throws SQLException {
		try (var delete = connection.prepareStatement("DELETE FROM bms.calibration_event WHERE id=?")) {
			delete.setLong(1, eventId);
			delete.executeUpdate();
		}
	}

	private static String environment(String key, String fallback) {
		var value = System.getenv(key);
		return value == null || value.isBlank() ? fallback : value;
	}

	private static final class FixedManager implements BmsStringManager {
		private final io.openems.edge.bms.service.strings.model.BmsStringModels.Definition definition;
		private final PostgresStringRepository repository;

		private FixedManager(io.openems.edge.bms.service.strings.model.BmsStringModels.Definition definition,
				PostgresStringRepository repository) {
			this.definition = definition;
			this.repository = repository;
		}

		@Override
		public io.openems.edge.bms.service.strings.model.BmsStringModels.Definition create(
				io.openems.edge.bms.service.strings.model.BmsStringModels.Definition request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public io.openems.edge.bms.service.strings.model.BmsStringModels.Definition update(
				io.openems.edge.bms.service.strings.model.BmsStringModels.Definition request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<io.openems.edge.bms.service.strings.model.BmsStringModels.Definition> list() {
			return List.of(this.definition);
		}

		@Override
		public io.openems.edge.bms.service.strings.model.BmsStringModels.Definition get(int stringIndex) {
			return this.definition;
		}

		@Override
		public void reconcile() throws SQLException {
			this.repository.synchronize(this.definition);
		}

		@Override
		public void delete(int stringIndex) {
			throw new UnsupportedOperationException();
		}

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
