package io.openems.edge.bms.service.persistence.implement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.LoggerFactory;

/**
 * Versioned business-data schema. Feature modules own the rows in these tables;
 * this class owns only schema creation and never OpenEMS component properties.
 */
final class FeatureSchemaMigration {

	static void migrate(BmsDatabase database) throws SQLException {
		try (var connection = database.getConnection(); var statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS string_index INTEGER");
			execute(statement,
					"CREATE UNIQUE INDEX IF NOT EXISTS bms_string_index_unique ON bms.bms_string(string_index)");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS bridge_id VARCHAR(128)");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS start_ta_unit_id INTEGER");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS cutoff_voltage NUMERIC");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS float_voltage NUMERIC");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS boost_voltage NUMERIC");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS rated_resistance_uohm NUMERIC");
			execute(statement, "ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS soc_checkpoint_policy VARCHAR(32) NOT NULL DEFAULT 'DISABLED'");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.soc_estimator_checkpoint (
					string_id UUID PRIMARY KEY REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					string_key VARCHAR(80) NOT NULL UNIQUE,
					schema_version SMALLINT NOT NULL CHECK (schema_version > 0),
					cell_count INTEGER NOT NULL CHECK (cell_count > 0),
					source_generation UUID NOT NULL,
					source_sequence BIGINT NOT NULL CHECK (source_sequence >= 0),
					measurement_time TIMESTAMPTZ NOT NULL,
					reference_event_id BIGINT NOT NULL,
					reference_curve_version_id BIGINT NOT NULL,
					reference_process_id BIGINT NOT NULL,
					reference_process_attempt INTEGER NOT NULL CHECK (reference_process_attempt >= 0),
					reference_content_hash VARCHAR(128) NOT NULL,
					cell_state JSONB NOT NULL,
					updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
		execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.site_setting (
					setting_key VARCHAR(128) PRIMARY KEY,
					setting_value JSONB NOT NULL,
					updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
					updated_by UUID REFERENCES bms.auth_user(id)
				)
				""");
		execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.asset_site_profile (
					id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
					owner TEXT, operational_team TEXT, substation TEXT, coordinates TEXT,
					maxicom_serial TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
					updated_by UUID REFERENCES bms.auth_user(id)
				)
				""");
		// Move only a valid legacy object. An existing relational row wins, but in
		// that case the legacy value remains available instead of being discarded.
		execute(statement, """
				WITH moved AS (
					INSERT INTO bms.asset_site_profile(id,owner,operational_team,substation,coordinates,maxicom_serial,updated_at,updated_by)
					SELECT 1, NULLIF(setting_value->>'owner',''), NULLIF(setting_value->>'operationalTeam',''),
						NULLIF(setting_value->>'substation',''), NULLIF(setting_value->>'coordinates',''),
						NULLIF(setting_value->>'maxicomSerial',''), updated_at, updated_by
					FROM bms.site_setting
					WHERE setting_key='asset_management.site_profile.v1' AND jsonb_typeof(setting_value)='object'
					AND NOT EXISTS (
						SELECT 1 FROM jsonb_each(CASE WHEN jsonb_typeof(setting_value)='object'
							THEN setting_value ELSE '{}'::jsonb END) AS field
						WHERE field.key IN ('owner','operationalTeam','substation','coordinates','maxicomSerial')
							AND jsonb_typeof(field.value) NOT IN ('string','null')
					)
					ON CONFLICT (id) DO NOTHING RETURNING id
				)
				DELETE FROM bms.site_setting WHERE setting_key='asset_management.site_profile.v1'
				AND EXISTS (SELECT 1 FROM moved)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.string_threshold (
					string_id UUID PRIMARY KEY REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					low_voltage NUMERIC, high_voltage NUMERIC, high_temperature NUMERIC,
					high_resistance NUMERIC, rated_resistance_uohm NUMERIC,
					updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.string_operation (
					id UUID PRIMARY KEY,
					string_id UUID REFERENCES bms.bms_string(id) ON DELETE SET NULL,
					operation_type VARCHAR(32) NOT NULL,
					status VARCHAR(32) NOT NULL,
					requested_by UUID REFERENCES bms.auth_user(id),
					error_message VARCHAR(1024),
					created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
					completed_at TIMESTAMPTZ
				)
				""");
		execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.asset (
					id UUID PRIMARY KEY, string_id UUID REFERENCES bms.bms_string(id) ON DELETE SET NULL,
					asset_type VARCHAR(64) NOT NULL, serial_number VARCHAR(160), manufacturer VARCHAR(160),
					model VARCHAR(160), manufacture_date DATE, installation_date DATE,
					replacement_soh_threshold NUMERIC, metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
					created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
		for (var column : new String[] {
				"dc_level TEXT", "battery_bank_id TEXT", "technology TEXT", "normal_voltage NUMERIC",
				"batch_lot_number TEXT", "commissioning_date DATE", "responsible_person TEXT",
				"sensor_mapping TEXT", "sensor_calibration_status TEXT", "current_limit NUMERIC",
				"test_ocv NUMERIC", "test_float_voltage NUMERIC", "test_ir NUMERIC",
				"test_negative_terminal_temp NUMERIC", "soh_warning_threshold NUMERIC", "replacement_date DATE" }) {
			execute(statement, "ALTER TABLE bms.asset ADD COLUMN IF NOT EXISTS " + column);
		}
		migrateAssetMetadata(connection);
		execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.asset_soh_history (
					id BIGSERIAL PRIMARY KEY, asset_id UUID NOT NULL REFERENCES bms.asset(id) ON DELETE CASCADE,
					soh NUMERIC NOT NULL, measured_at TIMESTAMPTZ NOT NULL, source VARCHAR(64) NOT NULL
				)
				""");
		// A profile belongs to the UUID of a string, never to its reusable index.
		// PostgreSQL deliberately rejects pre-existing duplicates instead of silently
		// discarding a user's profile during migration.
		execute(statement, "CREATE UNIQUE INDEX IF NOT EXISTS asset_bms_string_profile_unique "
				+ "ON bms.asset (string_id) WHERE asset_type='BMS_STRING_PROFILE' AND string_id IS NOT NULL");
		execute(statement, "ALTER TABLE bms.asset_soh_history ADD COLUMN IF NOT EXISTS source_key VARCHAR(128)");
		execute(statement, "CREATE INDEX IF NOT EXISTS asset_soh_history_asset_time_idx "
				+ "ON bms.asset_soh_history (asset_id, measured_at DESC)");
		execute(statement, "CREATE UNIQUE INDEX IF NOT EXISTS asset_soh_history_source_key_unique "
				+ "ON bms.asset_soh_history (asset_id, source, source_key) WHERE source_key IS NOT NULL");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.discharge_calibration_config (
					id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1), direction VARCHAR(16) NOT NULL,
					automatic_mode BOOLEAN NOT NULL, automatic_start_current_a NUMERIC NOT NULL,
					automatic_stop_current_a NUMERIC NOT NULL, sample_interval_seconds INTEGER NOT NULL,
					time_scale_factor NUMERIC NOT NULL, invalid_sample_limit INTEGER NOT NULL,
					rising_voltage_sample_limit INTEGER NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.discharge_event (
					id BIGSERIAL PRIMARY KEY, string_id UUID NOT NULL REFERENCES bms.bms_string(id),
					event_type VARCHAR(32) NOT NULL, baseline_event_id BIGINT REFERENCES bms.discharge_event(id),
					current_a NUMERIC, used_ah NUMERIC, nominal_capacity_ah NUMERIC, baseline_reference_ah NUMERIC, computed_soh NUMERIC,
					endpoint_reached BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(32) NOT NULL,
					automatic_mode BOOLEAN NOT NULL DEFAULT FALSE, active_baseline BOOLEAN NOT NULL DEFAULT FALSE,
					automatic_start_current_a NUMERIC, automatic_stop_current_a NUMERIC,
					sample_interval_seconds INTEGER NOT NULL DEFAULT 1800,
					time_scale_factor NUMERIC NOT NULL DEFAULT 1, invalid_sample_count INTEGER NOT NULL DEFAULT 0,
					last_sample_at TIMESTAMPTZ, failure_reason VARCHAR(512), start_time TIMESTAMPTZ NOT NULL, end_time TIMESTAMPTZ,
					current_direction VARCHAR(16), raw_current_a NUMERIC, domain_current_a NUMERIC,
					current_provenance VARCHAR(32), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
					updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS baseline_reference_ah NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS nominal_capacity_ah NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS endpoint_reached BOOLEAN NOT NULL DEFAULT FALSE");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS automatic_start_current_a NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS automatic_stop_current_a NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS sample_interval_seconds INTEGER NOT NULL DEFAULT 1800");
			execute(statement, "ALTER TABLE bms.discharge_event ALTER COLUMN sample_interval_seconds SET DEFAULT 1800");
			execute(statement, "UPDATE bms.discharge_calibration_config SET sample_interval_seconds=1800,updated_at=now() WHERE id=1 AND sample_interval_seconds=60");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS time_scale_factor NUMERIC NOT NULL DEFAULT 1");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS invalid_sample_count INTEGER NOT NULL DEFAULT 0");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS last_sample_at TIMESTAMPTZ");
			// Deliberately nullable: rows created before this migration retain unknown
			// current semantics and must not be silently reinterpreted.
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS current_direction VARCHAR(16)");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS raw_current_a NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS domain_current_a NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_event ADD COLUMN IF NOT EXISTS current_provenance VARCHAR(32)");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.discharge_sample (
					id BIGSERIAL PRIMARY KEY, event_id BIGINT NOT NULL REFERENCES bms.discharge_event(id) ON DELETE CASCADE,
					sample_time TIMESTAMPTZ NOT NULL, avg_voltage_v NUMERIC, current_a NUMERIC,
					delta_seconds NUMERIC, used_ah NUMERIC, temperature_c NUMERIC,
					raw_current_a NUMERIC, domain_current_a NUMERIC, current_direction VARCHAR(16),
					current_provenance VARCHAR(32), sample_valid BOOLEAN
				)
				""");
			execute(statement, "ALTER TABLE bms.discharge_sample ADD COLUMN IF NOT EXISTS raw_current_a NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_sample ADD COLUMN IF NOT EXISTS domain_current_a NUMERIC");
			execute(statement, "ALTER TABLE bms.discharge_sample ADD COLUMN IF NOT EXISTS current_direction VARCHAR(16)");
			execute(statement, "ALTER TABLE bms.discharge_sample ADD COLUMN IF NOT EXISTS current_provenance VARCHAR(32)");
			execute(statement, "ALTER TABLE bms.discharge_sample ADD COLUMN IF NOT EXISTS sample_valid BOOLEAN");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.soc_voltage_curve (
					id BIGSERIAL PRIMARY KEY, string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					source_event_id BIGINT NOT NULL REFERENCES bms.discharge_event(id), soc_pct NUMERIC NOT NULL,
					voltage_v NUMERIC NOT NULL, sample_count INTEGER NOT NULL, active BOOLEAN NOT NULL DEFAULT FALSE,
					created_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.soh_schedule (
					id UUID PRIMARY KEY, string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					schedule_type VARCHAR(32) NOT NULL, cron_expression VARCHAR(128), enabled BOOLEAN NOT NULL DEFAULT TRUE,
					created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.latest_value (
					string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					cell_index INTEGER, metric VARCHAR(64) NOT NULL, value NUMERIC, quality VARCHAR(32),
					measured_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (string_id, cell_index, metric)
				)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.measurement_raw (
					measured_at TIMESTAMPTZ NOT NULL, string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					cell_index INTEGER, metric VARCHAR(64) NOT NULL, value NUMERIC, quality VARCHAR(32) NOT NULL,
					PRIMARY KEY (measured_at, string_id, cell_index, metric)
				) PARTITION BY RANGE (measured_at)
				""");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.measurement_hourly (
					hour_start TIMESTAMPTZ NOT NULL, string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					cell_index INTEGER, metric VARCHAR(64) NOT NULL, min_value NUMERIC, max_value NUMERIC,
					avg_value NUMERIC, sample_count BIGINT NOT NULL,
					PRIMARY KEY (hour_start, string_id, cell_index, metric)
				)
				""");
			execute(statement, "CREATE INDEX IF NOT EXISTS measurement_hourly_string_metric_time ON bms.measurement_hourly(string_id, metric, hour_start DESC)");
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.email_alert_event (
					event_key VARCHAR(256) PRIMARY KEY, active BOOLEAN NOT NULL,
					last_sent_at TIMESTAMPTZ, last_delivery_status VARCHAR(16) NOT NULL,
					updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
				)
				""");
			migrateCalibrationEvents(statement);
			migrateMonitoringHistory(statement);
			migrateMonitoringAlarms(statement);
			migrateMonitoringPublication(statement);
			execute(statement,
					"INSERT INTO bms.schema_version(version) VALUES (2),(3),(4),(5),(6),(7),(8) ON CONFLICT DO NOTHING");
			connection.commit();
			LoggerFactory.getLogger(FeatureSchemaMigration.class).info("MaxiEMS BMS feature schema is ready.");
		} catch (SQLException e) {
			LoggerFactory.getLogger(FeatureSchemaMigration.class).error("Unable to initialize MaxiEMS BMS feature schema.", e);
			throw e;
		}
	}

	private static void execute(java.sql.Statement statement, String sql) throws SQLException {
		statement.execute(sql);
	}

	private static void migrateMonitoringPublication(java.sql.Statement statement) throws SQLException {
		// Monitoring remains operational before the first approved baseline. No
		// calibration tables or existing reference identities are rewritten here.
		for (var table : java.util.List.of("monitoring_raw", "monitoring_5m", "monitoring_hourly",
				"monitoring_alarm_occurrence", "monitoring_alarm_transition")) {
			for (var column : java.util.List.of("reference_event_id", "reference_curve_version_id",
					"reference_process_id", "reference_process_attempt", "reference_float_sample_id",
					"reference_voc_sample_id", "reference_content_hash")) {
				execute(statement, "ALTER TABLE bms." + table + " ALTER COLUMN " + column + " DROP NOT NULL");
			}
		}
		execute(statement, "ALTER TABLE bms.monitoring_alarm_processor_state"
				+ " ADD COLUMN IF NOT EXISTS last_publication_generation UUID");
		execute(statement, "ALTER TABLE bms.monitoring_alarm_processor_state"
				+ " ADD COLUMN IF NOT EXISTS last_publication_revision BIGINT");
	}

	private static void migrateMonitoringHistory(java.sql.Statement statement) throws SQLException {
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.monitoring_history_state (
				string_id UUID PRIMARY KEY REFERENCES bms.bms_string(id) ON DELETE CASCADE,
				last_seen_generation UUID,
				last_seen_sequence BIGINT CHECK (last_seen_sequence IS NULL OR last_seen_sequence >= 0),
				last_seen_captured_at TIMESTAMPTZ,
				last_persisted_captured_at TIMESTAMPTZ,
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.monitoring_raw (
				string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
				source_generation UUID NOT NULL,
				source_sequence BIGINT NOT NULL CHECK (source_sequence >= 0),
				captured_at TIMESTAMPTZ NOT NULL,
				current_measured_at TIMESTAMPTZ,
				current_quality VARCHAR(32) NOT NULL,
				stale BOOLEAN NOT NULL,
				reference_event_id BIGINT NOT NULL,
				reference_curve_version_id BIGINT NOT NULL,
				reference_process_id BIGINT NOT NULL,
				reference_process_attempt INTEGER NOT NULL CHECK (reference_process_attempt >= 0),
				reference_float_sample_id BIGINT NOT NULL,
				reference_voc_sample_id BIGINT NOT NULL,
				reference_content_hash VARCHAR(128) NOT NULL,
				operating_mode VARCHAR(24) NOT NULL,
				operating_mode_source VARCHAR(32) NOT NULL,
				operating_mode_quality VARCHAR(24) NOT NULL,
				current_direction_mapping VARCHAR(16) NOT NULL,
				alarm_mask INTEGER NOT NULL,
				quality_warning VARCHAR(512),
				battery_voltage_v DOUBLE PRECISION,
				midpoint_voltage_v DOUBLE PRECISION,
				battery_current_a DOUBLE PRECISION,
				battery_power_w DOUBLE PRECISION,
				float_current_a DOUBLE PRECISION,
				min_cell_voltage_v DOUBLE PRECISION,
				max_cell_voltage_v DOUBLE PRECISION,
				average_cell_voltage_v DOUBLE PRECISION,
				max_cell_resistance_uohm INTEGER,
				average_cell_resistance_uohm DOUBLE PRECISION,
				ambient_temperature_c DOUBLE PRECISION,
				max_cell_temperature_c DOUBLE PRECISION,
				average_cell_temperature_c DOUBLE PRECISION,
				battery_soc_pct DOUBLE PRECISION,
				remaining_discharge_hours DOUBLE PRECISION,
				battery_soh_pct DOUBLE PRECISION,
				minimum_cell_soh_pct DOUBLE PRECISION,
				persisted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				PRIMARY KEY (string_id,source_generation,source_sequence)
			)
			""");
		execute(statement,
				"CREATE INDEX IF NOT EXISTS monitoring_raw_string_time ON bms.monitoring_raw(string_id,captured_at DESC)");
		for (var table : new String[] { "monitoring_5m", "monitoring_hourly" }) {
			execute(statement, """
				CREATE TABLE IF NOT EXISTS bms.%s (
					string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
					bucket_start TIMESTAMPTZ NOT NULL,
					bucket_end TIMESTAMPTZ NOT NULL,
					sample_count BIGINT NOT NULL CHECK (sample_count > 0),
					first_captured_at TIMESTAMPTZ NOT NULL,
					last_captured_at TIMESTAMPTZ NOT NULL,
					reference_event_id BIGINT NOT NULL,
					reference_curve_version_id BIGINT NOT NULL,
					reference_process_id BIGINT NOT NULL,
					reference_process_attempt INTEGER NOT NULL CHECK (reference_process_attempt >= 0),
					reference_float_sample_id BIGINT NOT NULL,
					reference_voc_sample_id BIGINT NOT NULL,
					reference_content_hash VARCHAR(128) NOT NULL,
					mixed_reference BOOLEAN NOT NULL,
					last_operating_mode VARCHAR(24) NOT NULL,
					last_operating_mode_quality VARCHAR(24) NOT NULL,
					alarm_mask_or INTEGER NOT NULL,
					stale_sample_count BIGINT NOT NULL,
					metrics_min JSONB NOT NULL,
					metrics_max JSONB NOT NULL,
					metrics_avg JSONB NOT NULL,
					metrics_count JSONB NOT NULL,
					created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
					PRIMARY KEY (string_id,bucket_start),
					CHECK (bucket_end > bucket_start),
					CHECK (first_captured_at <= last_captured_at)
				)
				""".formatted(table));
			execute(statement, "ALTER TABLE bms." + table
					+ " ADD COLUMN IF NOT EXISTS reference_process_id BIGINT");
			execute(statement, "ALTER TABLE bms." + table
					+ " ADD COLUMN IF NOT EXISTS reference_process_attempt INTEGER"
					+ " CHECK (reference_process_attempt IS NULL OR reference_process_attempt >= 0)");
			execute(statement, "ALTER TABLE bms." + table
					+ " ADD COLUMN IF NOT EXISTS reference_float_sample_id BIGINT");
			execute(statement, "ALTER TABLE bms." + table
					+ " ADD COLUMN IF NOT EXISTS reference_voc_sample_id BIGINT");
			execute(statement, "ALTER TABLE bms." + table
					+ " ADD COLUMN IF NOT EXISTS metrics_count JSONB NOT NULL DEFAULT '{}'::jsonb");
			execute(statement, "CREATE INDEX IF NOT EXISTS " + table + "_string_time ON bms." + table
					+ "(string_id,bucket_start DESC)");
		}
	}

	private static void migrateMonitoringAlarms(java.sql.Statement statement) throws SQLException {
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.monitoring_alarm_processor_state (
				string_id UUID PRIMARY KEY REFERENCES bms.bms_string(id) ON DELETE CASCADE,
				last_generation UUID,
				last_sequence BIGINT CHECK (last_sequence IS NULL OR last_sequence >= 0),
				last_captured_at TIMESTAMPTZ,
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.monitoring_alarm_occurrence (
				id BIGSERIAL PRIMARY KEY,
				string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
				alarm_type VARCHAR(48) NOT NULL CHECK (alarm_type IN
					('LOW_CELL_VOLTAGE','HIGH_CELL_VOLTAGE','BATTERY_VOLTAGE_LOW',
					'BATTERY_VOLTAGE_HIGH','CELL_RESISTANCE','CELL_TEMPERATURE',
					'FLOAT_CHARGE_OVERCURRENT','CHARGE_OVERCURRENT','LOW_SOC',
					'HIGH_AMBIENT_TEMPERATURE','MIDPOINT_VOLTAGE','SENSOR_MODULE_FAILURE')),
				subject_key VARCHAR(32) NOT NULL,
				cell_index INTEGER CHECK (cell_index IS NULL OR cell_index > 0),
				status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE','LATCHED','CLEARED')),
				condition_active BOOLEAN NOT NULL,
				acknowledged_at TIMESTAMPTZ,
				acknowledged_by VARCHAR(160),
				raised_at TIMESTAMPTZ NOT NULL,
				condition_cleared_at TIMESTAMPTZ,
				cleared_at TIMESTAMPTZ,
				trigger_observed_value DOUBLE PRECISION,
				trigger_threshold_value DOUBLE PRECISION,
				trigger_reason VARCHAR(512),
				trigger_generation UUID NOT NULL,
				trigger_sequence BIGINT NOT NULL CHECK (trigger_sequence >= 0),
				trigger_captured_at TIMESTAMPTZ NOT NULL,
				reference_event_id BIGINT NOT NULL,
				reference_curve_version_id BIGINT NOT NULL,
				reference_process_id BIGINT NOT NULL,
				reference_process_attempt INTEGER NOT NULL CHECK (reference_process_attempt >= 0),
				reference_float_sample_id BIGINT NOT NULL,
				reference_voc_sample_id BIGINT NOT NULL,
				reference_content_hash VARCHAR(128) NOT NULL,
				active_calibration_event_id BIGINT,
				active_calibration_stage_id BIGINT,
				active_calibration_process_id BIGINT,
				active_event_type VARCHAR(32),
				active_stage_type VARCHAR(64),
				active_process_type VARCHAR(64),
				active_charge_current_limit_a DOUBLE PRECISION,
				policy_version INTEGER NOT NULL CHECK (policy_version > 0),
				raise_samples INTEGER NOT NULL CHECK (raise_samples > 0),
				clear_samples INTEGER NOT NULL CHECK (clear_samples > 0),
				hysteresis_value DOUBLE PRECISION NOT NULL CHECK (hysteresis_value >= 0),
				hysteresis_unit VARCHAR(32) NOT NULL CHECK
					(hysteresis_unit IN ('ABSOLUTE','THRESHOLD_FRACTION')),
				latching BOOLEAN NOT NULL,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				CHECK ((subject_key='STRING' AND cell_index IS NULL)
					OR (subject_key=('CELL:' || cell_index::text) AND cell_index IS NOT NULL)),
				CHECK ((status='CLEARED') = (cleared_at IS NOT NULL))
			)
			""");
		execute(statement, """
			CREATE UNIQUE INDEX IF NOT EXISTS monitoring_alarm_one_open_occurrence
			ON bms.monitoring_alarm_occurrence(string_id,alarm_type,subject_key)
			WHERE cleared_at IS NULL
			""");
		execute(statement, """
			CREATE INDEX IF NOT EXISTS monitoring_alarm_occurrence_string_time
			ON bms.monitoring_alarm_occurrence(string_id,raised_at DESC)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.monitoring_alarm_state (
				string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE CASCADE,
				alarm_type VARCHAR(48) NOT NULL CHECK (alarm_type IN
					('LOW_CELL_VOLTAGE','HIGH_CELL_VOLTAGE','BATTERY_VOLTAGE_LOW',
					'BATTERY_VOLTAGE_HIGH','CELL_RESISTANCE','CELL_TEMPERATURE',
					'FLOAT_CHARGE_OVERCURRENT','CHARGE_OVERCURRENT','LOW_SOC',
					'HIGH_AMBIENT_TEMPERATURE','MIDPOINT_VOLTAGE','SENSOR_MODULE_FAILURE')),
				subject_key VARCHAR(32) NOT NULL,
				cell_index INTEGER CHECK (cell_index IS NULL OR cell_index > 0),
				lifecycle_status VARCHAR(24) NOT NULL CHECK
					(lifecycle_status IN ('INACTIVE','PENDING_RAISE','ACTIVE','PENDING_CLEAR','LATCHED')),
				raise_count INTEGER NOT NULL DEFAULT 0 CHECK (raise_count >= 0),
				clear_count INTEGER NOT NULL DEFAULT 0 CHECK (clear_count >= 0),
				condition_active BOOLEAN NOT NULL DEFAULT FALSE,
				occurrence_id BIGINT REFERENCES bms.monitoring_alarm_occurrence(id) ON DELETE SET NULL,
				last_observed_value DOUBLE PRECISION,
				last_threshold_value DOUBLE PRECISION,
				last_reason VARCHAR(512),
				last_generation UUID,
				last_sequence BIGINT CHECK (last_sequence IS NULL OR last_sequence >= 0),
				last_captured_at TIMESTAMPTZ,
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				PRIMARY KEY (string_id,alarm_type,subject_key),
				CHECK ((subject_key='STRING' AND cell_index IS NULL)
					OR (subject_key=('CELL:' || cell_index::text) AND cell_index IS NOT NULL))
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.monitoring_alarm_transition (
				id BIGSERIAL PRIMARY KEY,
				occurrence_id BIGINT NOT NULL REFERENCES bms.monitoring_alarm_occurrence(id) ON DELETE CASCADE,
				transition_type VARCHAR(32) NOT NULL CHECK
					(transition_type IN ('RAISED','CONDITION_CLEARED','CONDITION_REACTIVATED','ACKNOWLEDGED','CLEARED')),
				transitioned_at TIMESTAMPTZ NOT NULL,
				actor VARCHAR(160),
				observed_value DOUBLE PRECISION,
				threshold_value DOUBLE PRECISION,
				reason VARCHAR(512),
				source_generation UUID NOT NULL,
				source_sequence BIGINT NOT NULL CHECK (source_sequence >= 0),
				source_captured_at TIMESTAMPTZ NOT NULL,
				reference_event_id BIGINT NOT NULL,
				reference_curve_version_id BIGINT NOT NULL,
				reference_process_id BIGINT NOT NULL,
				reference_process_attempt INTEGER NOT NULL CHECK (reference_process_attempt >= 0),
				reference_float_sample_id BIGINT NOT NULL,
				reference_voc_sample_id BIGINT NOT NULL,
				reference_content_hash VARCHAR(128) NOT NULL,
				policy_version INTEGER NOT NULL CHECK (policy_version > 0),
				raise_samples INTEGER NOT NULL CHECK (raise_samples > 0),
				clear_samples INTEGER NOT NULL CHECK (clear_samples > 0),
				hysteresis_value DOUBLE PRECISION NOT NULL CHECK (hysteresis_value >= 0),
				hysteresis_unit VARCHAR(32) NOT NULL CHECK
					(hysteresis_unit IN ('ABSOLUTE','THRESHOLD_FRACTION')),
				latching BOOLEAN NOT NULL
			)
			""");
		execute(statement, """
			CREATE UNIQUE INDEX IF NOT EXISTS monitoring_alarm_transition_frame_unique
			ON bms.monitoring_alarm_transition(occurrence_id,transition_type,source_generation,source_sequence)
			""");
	}

	private static void migrateCalibrationEvents(java.sql.Statement statement) throws SQLException {
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_event (
				id BIGSERIAL PRIMARY KEY,
				string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE RESTRICT,
				event_type VARCHAR(32) NOT NULL CONSTRAINT calibration_event_type_check CHECK
					(event_type IN ('INITIAL_BASELINE','MONTHLY_INSPECTION','SIX_MONTH_INSPECTION','PERIODIC_DISCHARGE')),
				status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' CONSTRAINT calibration_event_status_check CHECK
					(status IN ('DRAFT','ACTIVE','BLOCKED','REVALIDATION_REQUIRED','COMPLETED','ABORTED','CANCELLED')),
				current_stage_id BIGINT,
				event_version BIGINT NOT NULL DEFAULT 0 CONSTRAINT calibration_event_version_check CHECK (event_version >= 0),
				measurement_revision BIGINT NOT NULL DEFAULT 0 CONSTRAINT calibration_measurement_revision_check CHECK (measurement_revision >= 0),
				record_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (record_schema_version > 0),
				configuration_snapshot JSONB NOT NULL,
				reference_calibration_event_id BIGINT REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				created_by UUID NOT NULL REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				created_by_name VARCHAR(160) NOT NULL,
				started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, closed_at TIMESTAMPTZ,
				restart_revalidation_required BOOLEAN NOT NULL DEFAULT FALSE,
				blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
				missing_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
				final_result JSONB,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				CONSTRAINT calibration_event_time_order_check CHECK
					((started_at IS NULL OR completed_at IS NULL OR started_at <= completed_at)
					AND (started_at IS NULL OR closed_at IS NULL OR started_at <= closed_at)
					AND (completed_at IS NULL OR closed_at IS NULL OR completed_at <= closed_at)),
				CONSTRAINT calibration_event_lifecycle_check CHECK
					((status = 'DRAFT' AND current_stage_id IS NULL AND started_at IS NULL AND closed_at IS NULL)
					OR (status IN ('ACTIVE','BLOCKED','REVALIDATION_REQUIRED') AND current_stage_id IS NOT NULL
						AND started_at IS NOT NULL AND closed_at IS NULL)
					OR (status IN ('COMPLETED','ABORTED','CANCELLED') AND current_stage_id IS NULL AND closed_at IS NOT NULL))
			)
			""");
		execute(statement, """
			DO $$ BEGIN
				IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='bms'
					AND table_name='calibration_event' AND column_name='created_by_username')
					AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='bms'
					AND table_name='calibration_event' AND column_name='created_by_name') THEN
					ALTER TABLE bms.calibration_event RENAME COLUMN created_by_username TO created_by_name;
				END IF;
			END $$
			""");
		execute(statement, "ALTER TABLE bms.calibration_event DROP COLUMN IF EXISTS created_by_username");
		execute(statement, "ALTER TABLE bms.calibration_event DROP COLUMN IF EXISTS supervisor");
		execute(statement, "ALTER TABLE bms.calibration_event DROP COLUMN IF EXISTS work_order");
		execute(statement, "ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(160)");
		execute(statement, """
			UPDATE bms.calibration_event e SET created_by_name=COALESCE(u.display_name,u.username)
			FROM bms.auth_user u WHERE e.created_by=u.id AND e.created_by_name IS NULL
			""");
		execute(statement, "ALTER TABLE bms.calibration_event ALTER COLUMN created_by_name SET NOT NULL");
		execute(statement,
				"ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS measurement_revision BIGINT NOT NULL DEFAULT 0");
		execute(statement,
				"ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS record_schema_version SMALLINT NOT NULL DEFAULT 1");
		execute(statement,
				"ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS restart_revalidation_required BOOLEAN NOT NULL DEFAULT FALSE");
		execute(statement,
				"ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS blockers JSONB NOT NULL DEFAULT '[]'::jsonb");
		execute(statement,
				"ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS missing_evidence JSONB NOT NULL DEFAULT '[]'::jsonb");
		execute(statement, "ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS final_result JSONB");
		execute(statement, "ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS baseline_approved_at TIMESTAMPTZ");
		execute(statement, "ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS baseline_approved_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT");
		execute(statement, "ALTER TABLE bms.calibration_event ADD COLUMN IF NOT EXISTS baseline_approved_by_name VARCHAR(160)");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='calibration_baseline_approval_check'
					AND conrelid='bms.calibration_event'::regclass) THEN
					ALTER TABLE bms.calibration_event ADD CONSTRAINT calibration_baseline_approval_check CHECK
						((baseline_approved_at IS NULL AND baseline_approved_by IS NULL AND baseline_approved_by_name IS NULL)
						OR (event_type='INITIAL_BASELINE' AND status='COMPLETED' AND baseline_approved_at IS NOT NULL
							AND baseline_approved_by IS NOT NULL AND baseline_approved_by_name IS NOT NULL));
				END IF;
			END $$
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_stage (
				id BIGSERIAL PRIMARY KEY,
				calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				stage_order INTEGER NOT NULL CHECK (stage_order >= 0),
				stage_type VARCHAR(48) NOT NULL CONSTRAINT calibration_stage_type_check CHECK (stage_type IN
					('PREREQUISITES','INITIAL_CHARGE','PRE_DISCHARGE_FLOAT','WAITING_FOR_LOAD','DISCHARGE',
					 'LOAD_DISCONNECT','RECHARGE','POST_RECHARGE_FLOAT','BASELINE_CAPTURE','MONTHLY_INSPECTION',
					 'SIX_MONTH_INSPECTION','COMPLETION')),
				state VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED' CONSTRAINT calibration_stage_state_check CHECK
					(state IN ('NOT_STARTED','READY','ACTIVE','BLOCKED','REVALIDATION_REQUIRED','COMPLETED','SKIPPED','FAILED')),
				property_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (property_schema_version > 0),
				properties JSONB NOT NULL DEFAULT '{}'::jsonb,
				result VARCHAR(32) CONSTRAINT calibration_stage_result_check CHECK
					(result IS NULL OR result IN ('PASS','FAIL','COMPLETED_WITH_GAPS','NOT_APPLICABLE')),
				blocker_code VARCHAR(64), blocker_detail JSONB,
				started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
				started_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				completed_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				UNIQUE (calibration_event_id, stage_order), UNIQUE (id, calibration_event_id),
				CHECK (started_at IS NULL OR completed_at IS NULL OR started_at <= completed_at)
				)
				""");
		execute(statement,
				"ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS property_schema_version SMALLINT NOT NULL DEFAULT 1");
		execute(statement,
				"ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS properties JSONB NOT NULL DEFAULT '{}'::jsonb");
		execute(statement, "ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS result VARCHAR(32)");
		execute(statement, "ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS blocker_code VARCHAR(64)");
		execute(statement, "ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS blocker_detail JSONB");
		execute(statement, "ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ");
		execute(statement, "ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ");
		execute(statement,
				"ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS started_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT");
		execute(statement,
				"ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS completed_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT");
		execute(statement,
				"ALTER TABLE bms.calibration_stage ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='calibration_event_current_stage_fk'
					AND conrelid='bms.calibration_event'::regclass) THEN
					ALTER TABLE bms.calibration_event ADD CONSTRAINT calibration_event_current_stage_fk
					FOREIGN KEY (current_stage_id,id) REFERENCES bms.calibration_stage(id,calibration_event_id) ON DELETE RESTRICT;
				END IF;
			END $$
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_transition (
				id BIGSERIAL PRIMARY KEY, calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				calibration_stage_id BIGINT NOT NULL, from_state VARCHAR(32), to_state VARCHAR(32) NOT NULL,
				action VARCHAR(64) NOT NULL, reason VARCHAR(512),
				actor_id UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT, actor_name VARCHAR(160),
				evidence JSONB NOT NULL DEFAULT '{}'::jsonb, resulting_event_version BIGINT NOT NULL CHECK (resulting_event_version >= 0),
				transition_order SMALLINT NOT NULL DEFAULT 0 CHECK (transition_order >= 0), occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				UNIQUE (calibration_event_id,resulting_event_version,transition_order),
				FOREIGN KEY (calibration_stage_id,calibration_event_id)
					REFERENCES bms.calibration_stage(id,calibration_event_id) ON DELETE RESTRICT
			)
			""");
		execute(statement, """
			DO $$ BEGIN
				IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='bms'
					AND table_name='calibration_transition' AND column_name='actor_username')
					AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='bms'
					AND table_name='calibration_transition' AND column_name='actor_name') THEN
					ALTER TABLE bms.calibration_transition RENAME COLUMN actor_username TO actor_name;
				END IF;
			END $$
				""");
		execute(statement, "ALTER TABLE bms.calibration_transition DROP COLUMN IF EXISTS actor_username");
		execute(statement, "ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS reason VARCHAR(512)");
		execute(statement,
				"ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS actor_id UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT");
		execute(statement, "ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS actor_name VARCHAR(160)");
		execute(statement,
				"ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS evidence JSONB NOT NULL DEFAULT '{}'::jsonb");
		execute(statement,
				"ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS resulting_event_version BIGINT NOT NULL DEFAULT 0");
		execute(statement,
				"ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS transition_order SMALLINT NOT NULL DEFAULT 0");
		execute(statement,
				"ALTER TABLE bms.calibration_transition ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_process (
				id BIGSERIAL PRIMARY KEY,
				calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				calibration_stage_id BIGINT NOT NULL,
				process_order INTEGER NOT NULL CHECK (process_order >= 0),
				process_type VARCHAR(64) NOT NULL,
				attempt_number INTEGER NOT NULL DEFAULT 0 CHECK (attempt_number >= 0),
				state VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED' CHECK
					(state IN ('NOT_STARTED','ACTIVE','BLOCKED','COMPLETED','FAILED')),
				sampling_policy VARCHAR(32) NOT NULL CHECK
					(sampling_policy IN ('NONE','FINAL_SNAPSHOT','PERIODIC','CONTINUOUS')),
				properties JSONB NOT NULL DEFAULT '{}'::jsonb,
				started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
				started_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				completed_by UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				UNIQUE (calibration_stage_id,process_order), UNIQUE (id,calibration_event_id,calibration_stage_id),
				FOREIGN KEY (calibration_stage_id,calibration_event_id)
					REFERENCES bms.calibration_stage(id,calibration_event_id) ON DELETE RESTRICT,
				CHECK (started_at IS NULL OR completed_at IS NULL OR started_at <= completed_at)
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_process_transition (
				id BIGSERIAL PRIMARY KEY,
				calibration_event_id BIGINT NOT NULL, calibration_stage_id BIGINT NOT NULL,
				calibration_process_id BIGINT NOT NULL,
				from_state VARCHAR(32), to_state VARCHAR(32) NOT NULL, action VARCHAR(64) NOT NULL,
				reason VARCHAR(512), actor_id UUID REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				actor_name VARCHAR(160), resulting_event_version BIGINT NOT NULL CHECK (resulting_event_version >= 0),
				occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				FOREIGN KEY (calibration_process_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_process(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_sample (
				id BIGSERIAL PRIMARY KEY, calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				calibration_stage_id BIGINT NOT NULL, calibration_process_id BIGINT,
				process_attempt_number INTEGER CHECK (process_attempt_number IS NULL OR process_attempt_number >= 0),
				snapshot_generation UUID NOT NULL,
				snapshot_sequence BIGINT NOT NULL CHECK (snapshot_sequence >= 0), measurement_time TIMESTAMPTZ NOT NULL,
				persistence_reason VARCHAR(48) NOT NULL CONSTRAINT calibration_sample_reason_check CHECK (persistence_reason IN
					('PERIODIC_SAMPLE','FINAL_SNAPSHOT','QUALITY_CHANGE','CURRENT_PEAK','DOD_MILESTONE_5_PERCENT','TARGET_DOD','CUTOFF','SAFETY_FLOOR')),
				quality VARCHAR(32) NOT NULL CONSTRAINT calibration_sample_quality_check CHECK (quality IN
					('VALID','INVALID','STALE','PARTIAL','CONFIGURATION_MISMATCH','CURRENT_UNAVAILABLE','CELL_DATA_INCOMPLETE','LEGACY_UNKNOWN')),
				blocker_reason VARCHAR(64), raw_current_a NUMERIC, domain_current_a NUMERIC,
				current_direction VARCHAR(16) CHECK (current_direction IS NULL OR current_direction IN ('POSITIVE','NEGATIVE')),
				mapping_version BIGINT CHECK (mapping_version IS NULL OR mapping_version >= 0), current_measured_at TIMESTAMPTZ,
				current_quality VARCHAR(32), string_voltage_v NUMERIC, midpoint_voltage_v NUMERIC, average_voltage_v NUMERIC,
				ambient_temperature_c NUMERIC, average_cell_temperature_c NUMERIC,
				delta_seconds NUMERIC CHECK (delta_seconds IS NULL OR delta_seconds >= 0),
				delta_ah NUMERIC CHECK (delta_ah IS NULL OR delta_ah >= 0), raw_used_ah NUMERIC CHECK (raw_used_ah IS NULL OR raw_used_ah >= 0),
				representative_discharge_temperature_c NUMERIC,
				temperature_factor_kc NUMERIC CHECK (temperature_factor_kc IS NULL OR temperature_factor_kc > 0),
				corrected_used_ah NUMERIC CHECK (corrected_used_ah IS NULL OR corrected_used_ah >= 0),
				soc_pct NUMERIC CHECK (soc_pct IS NULL OR soc_pct BETWEEN 0 AND 100), soc_method VARCHAR(32),
				dod_pct NUMERIC CHECK (dod_pct IS NULL OR dod_pct >= 0), float_target_voltage_v NUMERIC,
				boost_target_voltage_v NUMERIC, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				UNIQUE (calibration_event_id,snapshot_generation,snapshot_sequence),
				FOREIGN KEY (calibration_stage_id,calibration_event_id)
					REFERENCES bms.calibration_stage(id,calibration_event_id) ON DELETE RESTRICT
				)
			""");
		addCalibrationSampleColumns(statement);
		execute(statement, "ALTER TABLE bms.calibration_sample DROP CONSTRAINT IF EXISTS calibration_sample_reason_check");
		execute(statement, "ALTER TABLE bms.calibration_sample ADD CONSTRAINT calibration_sample_reason_check CHECK "
				+ "(persistence_reason IN ('PERIODIC_SAMPLE','FINAL_SNAPSHOT','QUALITY_CHANGE','CURRENT_PEAK',"
				+ "'DOD_MILESTONE_5_PERCENT','TARGET_DOD','CUTOFF','SAFETY_FLOOR'))");
		execute(statement, "ALTER TABLE bms.calibration_sample ADD COLUMN IF NOT EXISTS calibration_process_id BIGINT");
		execute(statement,
				"ALTER TABLE bms.calibration_sample ADD COLUMN IF NOT EXISTS process_attempt_number INTEGER CHECK (process_attempt_number IS NULL OR process_attempt_number >= 0)");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='calibration_sample_event_stage_identity'
					AND conrelid='bms.calibration_sample'::regclass) THEN
					ALTER TABLE bms.calibration_sample ADD CONSTRAINT calibration_sample_event_stage_identity
					UNIQUE (id,calibration_event_id,calibration_stage_id);
				END IF;
			END $$
			""");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='calibration_sample_process_fk'
					AND conrelid='bms.calibration_sample'::regclass) THEN
					ALTER TABLE bms.calibration_sample ADD CONSTRAINT calibration_sample_process_fk
					FOREIGN KEY (calibration_process_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_process(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT;
				END IF;
			END $$
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_cell_sample (
				calibration_sample_id BIGINT NOT NULL REFERENCES bms.calibration_sample(id) ON DELETE RESTRICT,
				cell_index INTEGER NOT NULL CHECK (cell_index > 0), voltage_v NUMERIC, temperature_c NUMERIC,
				resistance_uohm INTEGER CHECK (resistance_uohm IS NULL OR resistance_uohm >= 0),
				soc_pct NUMERIC CHECK (soc_pct IS NULL OR soc_pct BETWEEN 0 AND 100), soc_method VARCHAR(32),
				soh_pct NUMERIC CHECK (soh_pct IS NULL OR soh_pct BETWEEN 0 AND 100), soh_method VARCHAR(32),
				quality VARCHAR(32) NOT NULL, availability_warning VARCHAR(64), PRIMARY KEY (calibration_sample_id,cell_index)
				)
				""");
		execute(statement,
				"ALTER TABLE bms.calibration_process ADD COLUMN IF NOT EXISTS attempt_number INTEGER NOT NULL DEFAULT 0 CHECK (attempt_number >= 0)");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS voltage_v NUMERIC");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS temperature_c NUMERIC");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS resistance_uohm INTEGER");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_sample_reason (
				calibration_sample_id BIGINT NOT NULL REFERENCES bms.calibration_sample(id) ON DELETE RESTRICT,
				persistence_reason VARCHAR(48) NOT NULL CHECK (persistence_reason IN
					('PERIODIC_SAMPLE','FINAL_SNAPSHOT','QUALITY_CHANGE','CURRENT_PEAK',
					 'DOD_MILESTONE_5_PERCENT','TARGET_DOD','CUTOFF','SAFETY_FLOOR')),
				reason_key VARCHAR(96) NOT NULL DEFAULT '',
				milestone_value NUMERIC,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				PRIMARY KEY (calibration_sample_id,persistence_reason,reason_key)
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_process_runtime (
				calibration_process_id BIGINT NOT NULL REFERENCES bms.calibration_process(id) ON DELETE RESTRICT,
				attempt_number INTEGER NOT NULL CHECK (attempt_number >= 0),
				snapshot_generation UUID,
				snapshot_sequence BIGINT CHECK (snapshot_sequence IS NULL OR snapshot_sequence >= 0),
				last_captured_at TIMESTAMPTZ,
				last_current_measured_at TIMESTAMPTZ,
				last_domain_current_a NUMERIC,
				last_periodic_persisted_at TIMESTAMPTZ,
				quality_signature VARCHAR(512),
				checkpoint_data JSONB NOT NULL DEFAULT '{}'::jsonb,
				latest_measurement JSONB NOT NULL DEFAULT '{}'::jsonb,
				blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
				recommendation VARCHAR(32),
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				PRIMARY KEY (calibration_process_id,attempt_number)
			)
			""");
		// Existing installations created this table as a discharge-specific envelope.
		// Add the common columns independently because CREATE TABLE IF NOT EXISTS does
		// not evolve an already existing table.
		execute(statement, "ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS blockers JSONB NOT NULL DEFAULT '[]'::jsonb");
		execute(statement, "ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS recommendation VARCHAR(32)");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint
					WHERE conname='calibration_runtime_blockers_array'
					AND conrelid='bms.calibration_process_runtime'::regclass) THEN
					ALTER TABLE bms.calibration_process_runtime
						ADD CONSTRAINT calibration_runtime_blockers_array
						CHECK (jsonb_typeof(blockers)='array');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_constraint
					WHERE conname='calibration_runtime_recommendation_check'
					AND conrelid='bms.calibration_process_runtime'::regclass) THEN
					ALTER TABLE bms.calibration_process_runtime
						ADD CONSTRAINT calibration_runtime_recommendation_check
						CHECK (recommendation IS NULL OR recommendation IN
							('CONTINUE','BLOCKED','READY_TO_COMPLETE'));
				END IF;
			END $$
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_discharge_runtime (
				calibration_process_id BIGINT NOT NULL,
				attempt_number INTEGER NOT NULL CHECK (attempt_number >= 0),
				last_delta_ah NUMERIC CHECK (last_delta_ah IS NULL OR last_delta_ah >= 0),
				raw_used_ah NUMERIC NOT NULL DEFAULT 0 CHECK (raw_used_ah >= 0),
				official_used_ah NUMERIC NOT NULL DEFAULT 0 CHECK (official_used_ah >= 0),
				next_dod_milestone_percent INTEGER NOT NULL DEFAULT 5
					CHECK (next_dod_milestone_percent BETWEEN 5 AND 105
						AND next_dod_milestone_percent % 5 = 0),
				per_cell_state JSONB NOT NULL DEFAULT '{}'::jsonb,
				crossed_cell_count INTEGER NOT NULL DEFAULT 0 CHECK (crossed_cell_count >= 0),
				configured_cell_count INTEGER CHECK (configured_cell_count IS NULL OR configured_cell_count > 0),
				string_safe_usable_capacity_ah NUMERIC
					CHECK (string_safe_usable_capacity_ah IS NULL OR string_safe_usable_capacity_ah >= 0),
				all_cells_endpoint_ah NUMERIC
					CHECK (all_cells_endpoint_ah IS NULL OR all_cells_endpoint_ah >= 0),
				safety_state VARCHAR(32) NOT NULL DEFAULT 'CLEAR'
					CHECK (safety_state IN ('CLEAR','LATCHED','STOP_CONFIRMED')),
				safety_triggered_at TIMESTAMPTZ,
				frozen_used_ah NUMERIC CHECK (frozen_used_ah IS NULL OR frozen_used_ah >= 0),
				safety_trigger_cells JSONB NOT NULL DEFAULT '[]'::jsonb,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				PRIMARY KEY (calibration_process_id,attempt_number),
				FOREIGN KEY (calibration_process_id,attempt_number)
					REFERENCES bms.calibration_process_runtime(calibration_process_id,attempt_number)
					ON DELETE RESTRICT
			)
			""");
		execute(statement, "ALTER TABLE bms.calibration_discharge_runtime ADD COLUMN IF NOT EXISTS crossed_cell_count INTEGER NOT NULL DEFAULT 0 CHECK (crossed_cell_count >= 0)");
		execute(statement, "ALTER TABLE bms.calibration_discharge_runtime ADD COLUMN IF NOT EXISTS configured_cell_count INTEGER CHECK (configured_cell_count IS NULL OR configured_cell_count > 0)");
		execute(statement, "ALTER TABLE bms.calibration_discharge_runtime ADD COLUMN IF NOT EXISTS string_safe_usable_capacity_ah NUMERIC CHECK (string_safe_usable_capacity_ah IS NULL OR string_safe_usable_capacity_ah >= 0)");
		execute(statement, "ALTER TABLE bms.calibration_discharge_runtime ADD COLUMN IF NOT EXISTS all_cells_endpoint_ah NUMERIC CHECK (all_cells_endpoint_ah IS NULL OR all_cells_endpoint_ah >= 0)");
		execute(statement, "ALTER TABLE bms.calibration_discharge_runtime ADD COLUMN IF NOT EXISTS safety_trigger_cells JSONB NOT NULL DEFAULT '[]'::jsonb");
		execute(statement, "ALTER TABLE bms.calibration_sample_reason DROP CONSTRAINT IF EXISTS calibration_sample_reason_persistence_reason_check");
		execute(statement, "ALTER TABLE bms.calibration_sample_reason ADD CONSTRAINT calibration_sample_reason_persistence_reason_check CHECK (persistence_reason IN ('PERIODIC_SAMPLE','FINAL_SNAPSHOT','QUALITY_CHANGE','CURRENT_PEAK','DOD_MILESTONE_5_PERCENT','TARGET_DOD','CUTOFF','SAFETY_FLOOR'))");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint
					WHERE conname='calibration_discharge_capacity_order'
					AND conrelid='bms.calibration_discharge_runtime'::regclass) THEN
					ALTER TABLE bms.calibration_discharge_runtime
						ADD CONSTRAINT calibration_discharge_capacity_order CHECK
							(string_safe_usable_capacity_ah IS NULL OR all_cells_endpoint_ah IS NULL
								OR string_safe_usable_capacity_ah <= all_cells_endpoint_ah);
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_constraint
					WHERE conname='calibration_discharge_safety_trigger_cells_array'
					AND conrelid='bms.calibration_discharge_runtime'::regclass) THEN
					ALTER TABLE bms.calibration_discharge_runtime
						ADD CONSTRAINT calibration_discharge_safety_trigger_cells_array
						CHECK (jsonb_typeof(safety_trigger_cells)='array');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_constraint
					WHERE conname='calibration_discharge_crossed_count'
					AND conrelid='bms.calibration_discharge_runtime'::regclass) THEN
					ALTER TABLE bms.calibration_discharge_runtime
						ADD CONSTRAINT calibration_discharge_crossed_count CHECK
							(configured_cell_count IS NULL OR crossed_cell_count <= configured_cell_count);
				END IF;
			END $$
			""");
		// Copy discharge-only state out of the old mixed runtime row only when an
		// installation actually still has legacy columns. Dynamic SQL avoids resolving
		// removed columns on fresh/already-split schemas. If a partially split schema
		// already has an extension row, the mixed row remains authoritative until these
		// columns are removed, so merge it instead of silently discarding its values.
		execute(statement, """
			DO $$ BEGIN
				IF EXISTS (SELECT 1 FROM information_schema.columns
					WHERE table_schema='bms' AND table_name='calibration_process_runtime'
						AND column_name IN ('last_delta_ah','raw_used_ah','official_used_ah',
							'next_dod_milestone_percent','per_cell_state','safety_state',
							'safety_triggered_at','frozen_used_ah')) THEN
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS last_delta_ah NUMERIC;
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS raw_used_ah NUMERIC NOT NULL DEFAULT 0;
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS official_used_ah NUMERIC NOT NULL DEFAULT 0;
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS next_dod_milestone_percent INTEGER NOT NULL DEFAULT 5;
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS per_cell_state JSONB NOT NULL DEFAULT '{}'::jsonb;
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS safety_state VARCHAR(32) NOT NULL DEFAULT 'CLEAR';
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS safety_triggered_at TIMESTAMPTZ;
					ALTER TABLE bms.calibration_process_runtime ADD COLUMN IF NOT EXISTS frozen_used_ah NUMERIC;
					EXECUTE $migration$
						INSERT INTO bms.calibration_discharge_runtime
							(calibration_process_id,attempt_number,last_delta_ah,raw_used_ah,official_used_ah,
							 next_dod_milestone_percent,per_cell_state,safety_state,safety_triggered_at,
							 frozen_used_ah,updated_at)
						SELECT runtime.calibration_process_id,runtime.attempt_number,runtime.last_delta_ah,
							runtime.raw_used_ah,runtime.official_used_ah,runtime.next_dod_milestone_percent,
							runtime.per_cell_state,runtime.safety_state,runtime.safety_triggered_at,
							runtime.frozen_used_ah,runtime.updated_at
						FROM bms.calibration_process_runtime runtime
						JOIN bms.calibration_process process ON process.id=runtime.calibration_process_id
						WHERE process.process_type='CONTROLLED_DISCHARGE'
						ON CONFLICT (calibration_process_id,attempt_number) DO UPDATE SET
							last_delta_ah=EXCLUDED.last_delta_ah,
							raw_used_ah=EXCLUDED.raw_used_ah,
							official_used_ah=EXCLUDED.official_used_ah,
							next_dod_milestone_percent=EXCLUDED.next_dod_milestone_percent,
							per_cell_state=EXCLUDED.per_cell_state,
							safety_state=EXCLUDED.safety_state,
							safety_triggered_at=EXCLUDED.safety_triggered_at,
							frozen_used_ah=EXCLUDED.frozen_used_ah,
							updated_at=EXCLUDED.updated_at
					$migration$;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN last_delta_ah;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN raw_used_ah;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN official_used_ah;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN next_dod_milestone_percent;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN per_cell_state;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN safety_state;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN safety_triggered_at;
					ALTER TABLE bms.calibration_process_runtime DROP COLUMN frozen_used_ah;
				END IF;
			END $$
			""");
		// Backfill the current attempt of every legacy automatic process, then remove
		// duplicate mutable runtime keys from its configuration JSON in the same schema
		// transaction. A worker still retains a read fallback for deployments upgraded
		// across versions.
		execute(statement, """
			INSERT INTO bms.calibration_process_runtime
				(calibration_process_id,attempt_number,checkpoint_data,latest_measurement,blockers,recommendation,updated_at)
			SELECT process.id,process.attempt_number,
				COALESCE(process.properties->'checkpoint','{}'::jsonb),
				COALESCE(process.properties->'latestMeasurement','{}'::jsonb),
				CASE WHEN jsonb_typeof(process.properties->'blockers')='array'
					THEN process.properties->'blockers' ELSE '[]'::jsonb END,
				process.properties->>'recommendation',now()
			FROM bms.calibration_process process
			WHERE process.properties ?| ARRAY['checkpoint','latestMeasurement','blockers','recommendation']
			ON CONFLICT (calibration_process_id,attempt_number) DO UPDATE SET
				checkpoint_data=CASE
					WHEN bms.calibration_process_runtime.checkpoint_data='{}'::jsonb
					THEN EXCLUDED.checkpoint_data ELSE bms.calibration_process_runtime.checkpoint_data END,
				latest_measurement=CASE
					WHEN bms.calibration_process_runtime.latest_measurement='{}'::jsonb
					THEN EXCLUDED.latest_measurement ELSE bms.calibration_process_runtime.latest_measurement END,
				blockers=CASE WHEN bms.calibration_process_runtime.blockers='[]'::jsonb
					THEN EXCLUDED.blockers ELSE bms.calibration_process_runtime.blockers END,
				recommendation=COALESCE(bms.calibration_process_runtime.recommendation,EXCLUDED.recommendation),
				updated_at=now()
			""");
		execute(statement, """
			UPDATE bms.calibration_process process
			SET properties=process.properties-ARRAY['checkpoint','latestMeasurement','blockers','recommendation']::text[],
				updated_at=now()
			WHERE process.properties ?| ARRAY['checkpoint','latestMeasurement','blockers','recommendation']
				AND EXISTS (SELECT 1 FROM bms.calibration_process_runtime runtime
					WHERE runtime.calibration_process_id=process.id
					AND runtime.attempt_number=process.attempt_number)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_cell_cutoff_crossing (
				id BIGSERIAL PRIMARY KEY,
				calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				calibration_stage_id BIGINT NOT NULL,
				calibration_process_id BIGINT NOT NULL,
				process_attempt_number INTEGER NOT NULL CHECK (process_attempt_number >= 0),
				cell_index INTEGER NOT NULL CHECK (cell_index > 0),
				before_sample_id BIGINT REFERENCES bms.calibration_sample(id) ON DELETE RESTRICT,
				after_sample_id BIGINT NOT NULL REFERENCES bms.calibration_sample(id) ON DELETE RESTRICT,
				cutoff_voltage_v NUMERIC NOT NULL,
				crossing_measurement_time TIMESTAMPTZ NOT NULL,
				cell_usable_capacity_ah NUMERIC NOT NULL CHECK (cell_usable_capacity_ah >= 0),
				interpolation_fraction NUMERIC CHECK (interpolation_fraction IS NULL OR interpolation_fraction BETWEEN 0 AND 1),
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				UNIQUE (calibration_process_id,process_attempt_number,cell_index),
				FOREIGN KEY (before_sample_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_sample(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT,
				FOREIGN KEY (after_sample_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_sample(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT,
				FOREIGN KEY (calibration_process_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_process(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT
			)
			""");
		execute(statement, "ALTER TABLE bms.calibration_cell_cutoff_crossing ALTER COLUMN interpolation_fraction DROP NOT NULL");
		execute(statement, """
			UPDATE bms.calibration_discharge_runtime runtime
			SET crossed_cell_count=(SELECT count(*) FROM bms.calibration_cell_cutoff_crossing crossing
					WHERE crossing.calibration_process_id=runtime.calibration_process_id
						AND crossing.process_attempt_number=runtime.attempt_number),
				configured_cell_count=COALESCE(
					NULLIF(event.configuration_snapshot->>'cellCount','')::integer,
					runtime.configured_cell_count),
				string_safe_usable_capacity_ah=(SELECT min(cell_usable_capacity_ah)
					FROM bms.calibration_cell_cutoff_crossing crossing
					WHERE crossing.calibration_process_id=runtime.calibration_process_id
						AND crossing.process_attempt_number=runtime.attempt_number),
				all_cells_endpoint_ah=(SELECT max(cell_usable_capacity_ah)
					FROM bms.calibration_cell_cutoff_crossing crossing
					WHERE crossing.calibration_process_id=runtime.calibration_process_id
						AND crossing.process_attempt_number=runtime.attempt_number),
				updated_at=now()
			FROM bms.calibration_process process
			JOIN bms.calibration_event event ON event.id=process.calibration_event_id
			WHERE process.id=runtime.calibration_process_id
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_curve_version (
				id BIGSERIAL PRIMARY KEY,
				calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				string_id UUID NOT NULL REFERENCES bms.bms_string(id) ON DELETE RESTRICT,
				calibration_stage_id BIGINT NOT NULL,
				calibration_process_id BIGINT NOT NULL,
				process_attempt_number INTEGER NOT NULL CHECK (process_attempt_number >= 0),
				curve_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (curve_schema_version > 0),
				capacity_reference_ah NUMERIC NOT NULL CHECK (capacity_reference_ah > 0),
				string_safe_usable_capacity_ah NUMERIC NOT NULL CHECK (string_safe_usable_capacity_ah >= 0),
				all_cells_endpoint_ah NUMERIC NOT NULL CHECK (all_cells_endpoint_ah >= 0),
				cell_count INTEGER NOT NULL CHECK (cell_count > 0),
				point_count INTEGER NOT NULL CHECK (point_count >= 0),
				content_hash VARCHAR(128) NOT NULL,
				published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				UNIQUE (calibration_process_id,process_attempt_number),
				UNIQUE (id,string_id,calibration_event_id),
				FOREIGN KEY (calibration_process_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_process(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT,
				CHECK (string_safe_usable_capacity_ah <= all_cells_endpoint_ah)
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_curve_point (
				curve_version_id BIGINT NOT NULL REFERENCES bms.calibration_curve_version(id) ON DELETE RESTRICT,
				cell_index INTEGER NOT NULL CHECK (cell_index > 0),
				point_order INTEGER NOT NULL CHECK (point_order >= 0),
				calibration_sample_id BIGINT NOT NULL REFERENCES bms.calibration_sample(id) ON DELETE RESTRICT,
				milestone_percent NUMERIC NOT NULL CHECK (milestone_percent >= 0),
				normalized_actual_dod_percent NUMERIC NOT NULL CHECK (normalized_actual_dod_percent >= 0),
				discharged_ah NUMERIC NOT NULL CHECK (discharged_ah >= 0),
				cell_voltage_v NUMERIC NOT NULL,
				cell_temperature_c NUMERIC,
				cell_resistance_uohm INTEGER CHECK (cell_resistance_uohm IS NULL OR cell_resistance_uohm >= 0),
				cell_soc_pct NUMERIC CHECK (cell_soc_pct IS NULL OR cell_soc_pct BETWEEN 0 AND 100),
				cell_soh_pct NUMERIC CHECK (cell_soh_pct IS NULL OR cell_soh_pct BETWEEN 0 AND 100),
				string_voltage_v NUMERIC,
				average_voltage_v NUMERIC,
				quality VARCHAR(32) NOT NULL,
				usable BOOLEAN NOT NULL,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				PRIMARY KEY (curve_version_id,cell_index,point_order),
				UNIQUE (curve_version_id,cell_index,milestone_percent)
			)
			""");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_active_baseline (
				string_id UUID PRIMARY KEY REFERENCES bms.bms_string(id) ON DELETE RESTRICT,
				curve_version_id BIGINT NOT NULL UNIQUE REFERENCES bms.calibration_curve_version(id) ON DELETE RESTRICT,
				calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				FOREIGN KEY (curve_version_id,string_id,calibration_event_id)
					REFERENCES bms.calibration_curve_version(id,string_id,calibration_event_id) ON DELETE RESTRICT
			)
			""");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS soc_pct NUMERIC");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS soc_method VARCHAR(32)");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS soh_pct NUMERIC");
		execute(statement, "ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS soh_method VARCHAR(32)");
		execute(statement,
				"ALTER TABLE bms.calibration_cell_sample ADD COLUMN IF NOT EXISTS availability_warning VARCHAR(64)");
		execute(statement, "COMMENT ON COLUMN bms.calibration_sample.soc_pct IS "
				+ "'Deprecated compatibility field; not authoritative Initial Baseline evidence'");
		execute(statement, "COMMENT ON COLUMN bms.calibration_cell_sample.soc_pct IS "
				+ "'Deprecated compatibility field; not authoritative Initial Baseline evidence'");
		execute(statement, "COMMENT ON COLUMN bms.calibration_cell_sample.soh_pct IS "
				+ "'Deprecated compatibility field; not authoritative Initial Baseline evidence'");
		execute(statement, "COMMENT ON COLUMN bms.calibration_curve_point.cell_soc_pct IS "
				+ "'Deprecated compatibility field; new Initial Baseline curves store NULL'");
		execute(statement, "COMMENT ON COLUMN bms.calibration_curve_point.cell_soh_pct IS "
				+ "'Deprecated compatibility field; new Initial Baseline curves store NULL'");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_manual_evidence (
				id BIGSERIAL PRIMARY KEY, calibration_event_id BIGINT NOT NULL REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				calibration_stage_id BIGINT NOT NULL, evidence_type VARCHAR(64) NOT NULL,
				status VARCHAR(40) NOT NULL CONSTRAINT calibration_evidence_status_check CHECK (status IN
					('RECORDED','PASS','FAIL','MISSING_NOT_AVAILABLE','NOT_APPLICABLE','DATA_INVALID')),
				numeric_value NUMERIC, text_value TEXT, unit VARCHAR(32), instrument_id VARCHAR(128),
				instrument_resolution NUMERIC CHECK (instrument_resolution IS NULL OR instrument_resolution >= 0),
				instrument_accuracy NUMERIC CHECK (instrument_accuracy IS NULL OR instrument_accuracy >= 0),
				calibration_reference VARCHAR(256), actor_id UUID NOT NULL REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				actor_name VARCHAR(160) NOT NULL, note TEXT, external_reference VARCHAR(512), calibration_sample_id BIGINT,
				recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				FOREIGN KEY (calibration_stage_id,calibration_event_id)
					REFERENCES bms.calibration_stage(id,calibration_event_id) ON DELETE RESTRICT
			)
			""");
		execute(statement, """
			DO $$ BEGIN
				IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='bms'
					AND table_name='calibration_manual_evidence' AND column_name='actor_username')
					AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='bms'
					AND table_name='calibration_manual_evidence' AND column_name='actor_name') THEN
					ALTER TABLE bms.calibration_manual_evidence RENAME COLUMN actor_username TO actor_name;
				END IF;
			END $$
				""");
		execute(statement, "ALTER TABLE bms.calibration_manual_evidence DROP COLUMN IF EXISTS actor_username");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS actor_name VARCHAR(160)");
		// An earlier schema version may already have installed the append-only trigger.
		// Drop it transactionally while backfilling the newly added actor column; the
		// canonical trigger loop below recreates it before this migration commits.
		execute(statement,
				"DROP TRIGGER IF EXISTS calibration_manual_evidence_append_only ON bms.calibration_manual_evidence");
		execute(statement, """
			UPDATE bms.calibration_manual_evidence e SET actor_name=COALESCE(u.display_name,u.username)
			FROM bms.auth_user u WHERE e.actor_id=u.id AND e.actor_name IS NULL
			""");
		execute(statement, "ALTER TABLE bms.calibration_manual_evidence ALTER COLUMN actor_name SET NOT NULL");
		execute(statement, "ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS numeric_value NUMERIC");
		execute(statement, "ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS text_value TEXT");
		execute(statement, "ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS unit VARCHAR(32)");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS instrument_id VARCHAR(128)");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS instrument_resolution NUMERIC");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS instrument_accuracy NUMERIC");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS calibration_reference VARCHAR(256)");
		execute(statement, "ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS note TEXT");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS external_reference VARCHAR(512)");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS calibration_sample_id BIGINT");
		execute(statement, """
			DO $$ BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='calibration_evidence_sample_fk'
					AND conrelid='bms.calibration_manual_evidence'::regclass) THEN
					ALTER TABLE bms.calibration_manual_evidence ADD CONSTRAINT calibration_evidence_sample_fk
					FOREIGN KEY (calibration_sample_id,calibration_event_id,calibration_stage_id)
					REFERENCES bms.calibration_sample(id,calibration_event_id,calibration_stage_id) ON DELETE RESTRICT;
				END IF;
			END $$
			""");
		execute(statement,
				"ALTER TABLE bms.calibration_manual_evidence ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()");
		execute(statement, """
			CREATE TABLE IF NOT EXISTS bms.calibration_report (
				calibration_event_id BIGINT PRIMARY KEY REFERENCES bms.calibration_event(id) ON DELETE RESTRICT,
				report_schema_version SMALLINT NOT NULL CHECK (report_schema_version > 0), report_data JSONB NOT NULL,
				generated_at TIMESTAMPTZ NOT NULL DEFAULT now(), generated_by UUID NOT NULL REFERENCES bms.auth_user(id) ON DELETE RESTRICT,
				content_hash VARCHAR(128) NOT NULL
			)
			""");
		execute(statement, "CREATE UNIQUE INDEX IF NOT EXISTS calibration_event_one_open_per_string ON bms.calibration_event(string_id) WHERE closed_at IS NULL");
		execute(statement, "CREATE UNIQUE INDEX IF NOT EXISTS calibration_stage_one_current_per_event ON bms.calibration_stage(calibration_event_id) WHERE state IN ('ACTIVE','BLOCKED','REVALIDATION_REQUIRED')");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_event_history ON bms.calibration_event(string_id,created_at DESC)");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_sample_event_stage_time ON bms.calibration_sample(calibration_event_id,calibration_stage_id,measurement_time)");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_process_active ON bms.calibration_process(calibration_event_id,calibration_stage_id) WHERE state IN ('ACTIVE','BLOCKED')");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_process_transition_history ON bms.calibration_process_transition(calibration_event_id,calibration_stage_id,occurred_at)");
		execute(statement,
				"ALTER TABLE bms.calibration_process_transition ADD COLUMN IF NOT EXISTS evidence JSONB NOT NULL DEFAULT '{}'::jsonb");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_manual_evidence_event_stage ON bms.calibration_manual_evidence(calibration_event_id,calibration_stage_id,recorded_at)");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_runtime_updated ON bms.calibration_process_runtime(updated_at)");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_cutoff_event_attempt ON bms.calibration_cell_cutoff_crossing(calibration_event_id,calibration_process_id,process_attempt_number)");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_curve_string_published ON bms.calibration_curve_version(string_id,published_at DESC)");
		execute(statement, "CREATE INDEX IF NOT EXISTS calibration_curve_point_sample ON bms.calibration_curve_point(calibration_sample_id)");

		execute(statement, """
			CREATE OR REPLACE FUNCTION bms.reject_calibration_append_only_mutation() RETURNS trigger AS $$
			BEGIN RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE='55000'; END; $$ LANGUAGE plpgsql
			""");
		for (var table : new String[] { "calibration_transition", "calibration_process_transition", "calibration_sample", "calibration_sample_reason", "calibration_cell_sample",
				"calibration_cell_cutoff_crossing", "calibration_curve_version", "calibration_curve_point",
				"calibration_manual_evidence", "calibration_report" }) {
			execute(statement, "DROP TRIGGER IF EXISTS " + table + "_append_only ON bms." + table);
			execute(statement, "CREATE TRIGGER " + table + "_append_only BEFORE UPDATE OR DELETE ON bms." + table
					+ " FOR EACH ROW EXECUTE FUNCTION bms.reject_calibration_append_only_mutation()");
		}
		execute(statement, """
			CREATE OR REPLACE FUNCTION bms.guard_calibration_event_mutation() RETURNS trigger AS $$
			BEGIN
				IF TG_OP='DELETE' THEN
					IF OLD.status <> 'DRAFT' OR OLD.current_stage_id IS NOT NULL
							OR OLD.started_at IS NOT NULL OR OLD.closed_at IS NOT NULL
							OR NOT COALESCE(current_setting('bms.calibration.delete_draft_event_id',true)=OLD.id::text,false) THEN
						RAISE EXCEPTION 'calibration events cannot be hard-deleted' USING ERRCODE='55000';
					END IF;
					RETURN OLD;
				END IF;
				IF OLD.closed_at IS NOT NULL THEN RAISE EXCEPTION 'closed calibration events are immutable' USING ERRCODE='55000'; END IF;
				IF NEW.configuration_snapshot IS DISTINCT FROM OLD.configuration_snapshot THEN
					RAISE EXCEPTION 'calibration configuration snapshot is immutable' USING ERRCODE='55000';
				END IF;
				RETURN NEW;
			END; $$ LANGUAGE plpgsql
			""");
		execute(statement, "DROP TRIGGER IF EXISTS calibration_event_immutable ON bms.calibration_event");
		execute(statement, "CREATE TRIGGER calibration_event_immutable BEFORE UPDATE OR DELETE ON bms.calibration_event FOR EACH ROW EXECUTE FUNCTION bms.guard_calibration_event_mutation()");
		execute(statement, """
			CREATE OR REPLACE FUNCTION bms.guard_calibration_stage_mutation() RETURNS trigger AS $$
			BEGIN
				IF TG_OP='DELETE' THEN
					IF OLD.state <> 'NOT_STARTED' OR OLD.started_at IS NOT NULL OR OLD.completed_at IS NOT NULL
							OR NOT COALESCE(current_setting('bms.calibration.delete_draft_event_id',true)=OLD.calibration_event_id::text,false)
							OR NOT EXISTS (SELECT 1 FROM bms.calibration_event e
								WHERE e.id=OLD.calibration_event_id AND e.status='DRAFT'
								AND e.current_stage_id IS NULL AND e.started_at IS NULL AND e.closed_at IS NULL) THEN
						RAISE EXCEPTION 'calibration stages cannot be hard-deleted' USING ERRCODE='55000';
					END IF;
					RETURN OLD;
				END IF;
				IF EXISTS (SELECT 1 FROM bms.calibration_event e
					WHERE e.id=OLD.calibration_event_id AND e.closed_at IS NOT NULL) THEN
					RAISE EXCEPTION 'terminal calibration stages are immutable' USING ERRCODE='55000';
				END IF;
				IF OLD.state IN ('COMPLETED','SKIPPED','FAILED') AND NOT COALESCE((
					current_setting('bms.calibration.redo_event_id',true)=OLD.calibration_event_id::text
					AND OLD.stage_order>=NULLIF(current_setting('bms.calibration.redo_stage_order',true),'')::integer),false) THEN
					RAISE EXCEPTION 'terminal calibration stages are immutable' USING ERRCODE='55000';
				END IF;
				RETURN NEW;
			END; $$ LANGUAGE plpgsql
			""");
		execute(statement, "DROP TRIGGER IF EXISTS calibration_stage_immutable ON bms.calibration_stage");
		execute(statement, "CREATE TRIGGER calibration_stage_immutable BEFORE UPDATE OR DELETE ON bms.calibration_stage FOR EACH ROW EXECUTE FUNCTION bms.guard_calibration_stage_mutation()");
	}

	private static void addCalibrationSampleColumns(java.sql.Statement statement) throws SQLException {
		for (var definition : new String[] {
				"blocker_reason VARCHAR(64)", "raw_current_a NUMERIC", "domain_current_a NUMERIC",
				"current_direction VARCHAR(16)", "mapping_version BIGINT", "current_measured_at TIMESTAMPTZ",
				"current_quality VARCHAR(32)", "string_voltage_v NUMERIC", "midpoint_voltage_v NUMERIC", "average_voltage_v NUMERIC",
				"ambient_temperature_c NUMERIC", "average_cell_temperature_c NUMERIC", "delta_seconds NUMERIC",
				"delta_ah NUMERIC", "raw_used_ah NUMERIC", "representative_discharge_temperature_c NUMERIC",
				"temperature_factor_kc NUMERIC", "corrected_used_ah NUMERIC", "soc_pct NUMERIC",
				"soc_method VARCHAR(32)", "dod_pct NUMERIC", "float_target_voltage_v NUMERIC",
				"boost_target_voltage_v NUMERIC", "created_at TIMESTAMPTZ NOT NULL DEFAULT now()" }) {
			execute(statement, "ALTER TABLE bms.calibration_sample ADD COLUMN IF NOT EXISTS " + definition);
		}
	}

	/** Copies recognized legacy JSON values into nullable relational columns.
	 * Migrated keys are removed atomically; unknown or invalid values are retained. */
	private static void migrateAssetMetadata(Connection connection) throws SQLException {
		var keys = new String[] { "dcLevel", "batteryBankId", "manufacturer", "technology", "normalVoltage",
				"batchLotNumber", "manufacturingDate", "commissioningDate", "installationDate", "responsiblePerson",
				"sensorMapping", "sensorCalibrationStatus", "currentLimit", "testOcv", "testFloatVoltage", "testIr",
				"testNegativeTerminalTemp", "sohWarningThreshold", "sohReplaceThreshold", "replacementDate" };
		var columns = new String[] { "dc_level", "battery_bank_id", "manufacturer", "technology", "normal_voltage",
				"batch_lot_number", "manufacture_date", "commissioning_date", "installation_date", "responsible_person",
				"sensor_mapping", "sensor_calibration_status", "current_limit", "test_ocv", "test_float_voltage",
				"test_ir", "test_negative_terminal_temp", "soh_warning_threshold", "replacement_soh_threshold",
				"replacement_date" };
		var sql = new StringBuilder("UPDATE bms.asset SET ");
		for (int i = 0; i < columns.length; i++) {
			if (i > 0) sql.append(',');
			sql.append(columns[i]).append("=COALESCE(").append(columns[i]).append(",?)");
		}
		sql.append(",metadata=CAST(? AS jsonb) WHERE id=? AND asset_type='BMS_STRING_PROFILE'");
		try (var query = connection.prepareStatement(
				"SELECT id,metadata::text," + String.join(",", columns)
						+ " FROM bms.asset WHERE asset_type='BMS_STRING_PROFILE' AND metadata<>'{}'::jsonb");
				var rows = query.executeQuery(); var update = connection.prepareStatement(sql.toString())) {
			while (rows.next()) {
				JsonObject metadata;
				try {
					var parsed = JsonParser.parseString(rows.getString(2));
					if (!parsed.isJsonObject()) continue;
					metadata = parsed.getAsJsonObject();
				} catch (RuntimeException e) {
					continue;
				}
			for (int i = 0; i < keys.length; i++) {
				var value = metadata.get(keys[i]);
				String text = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
						? value.getAsString().trim() : null;
				if (text == null) { update.setObject(i + 1, null); continue; }
				var current = rows.getObject(i + 3);
				if (text.isEmpty()) {
					update.setObject(i + 1, null);
					if (current == null) metadata.remove(keys[i]);
					continue;
				}
				if (i == 2 && text.length() > 160) { update.setObject(i + 1, null); continue; }
				Object converted;
				if (i == 6 || i == 7 || i == 8 || i == 19) {
					try { converted = Date.valueOf(LocalDate.parse(text)); }
					catch (RuntimeException e) { update.setObject(i + 1, null); continue; }
				} else if (i == 4 || i >= 12 && i <= 18) {
					try { converted = new BigDecimal(text); }
					catch (RuntimeException e) { update.setObject(i + 1, null); continue; }
				} else {
					converted = text;
				}
				if (current == null) {
					update.setObject(i + 1, converted);
					metadata.remove(keys[i]);
				} else {
					update.setObject(i + 1, null);
					if (sameAssetValue(current, converted)) metadata.remove(keys[i]);
				}
				}
				update.setString(keys.length + 1, metadata.toString());
				update.setObject(keys.length + 2, rows.getObject(1));
				update.executeUpdate();
			}
		}
	}

	private static boolean sameAssetValue(Object current, Object converted) {
		if (current instanceof BigDecimal left && converted instanceof BigDecimal right) {
			return left.compareTo(right) == 0;
		}
		return current.equals(converted);
	}
}
