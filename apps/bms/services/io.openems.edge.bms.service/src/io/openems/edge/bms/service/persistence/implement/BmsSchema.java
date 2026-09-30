package io.openems.edge.bms.service.persistence.implement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

final class BmsSchema {

	private BmsSchema() {
	}

	static void migrate(DataSource dataSource) throws SQLException {
		migrate(dataSource::getConnection);
	}

	static void migrate(BmsDatabase database) throws SQLException {
		migrate(database::getConnection);
	}

	private static void migrate(ConnectionSource source) throws SQLException {
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			execute(statement, "CREATE SCHEMA IF NOT EXISTS bms");
			execute(statement, "CREATE TABLE IF NOT EXISTS bms.schema_version (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
			execute(statement, """
					CREATE TABLE IF NOT EXISTS bms.auth_user (
						id UUID PRIMARY KEY,
						username VARCHAR(128) NOT NULL UNIQUE,
						display_name VARCHAR(160) NOT NULL,
						password_hash BYTEA NOT NULL,
						salt BYTEA NOT NULL,
						enabled BOOLEAN NOT NULL DEFAULT TRUE,
						created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
						updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
						password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
					)
					""");
			execute(statement, "ALTER TABLE bms.auth_user ADD COLUMN IF NOT EXISTS display_name VARCHAR(160)");
			execute(statement, "UPDATE bms.auth_user SET display_name=username WHERE display_name IS NULL OR btrim(display_name)=''");
			execute(statement, "ALTER TABLE bms.auth_user ALTER COLUMN display_name SET NOT NULL");
			execute(statement, """
					DO $$ BEGIN
						IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='auth_user_display_name_check'
							AND conrelid='bms.auth_user'::regclass) THEN
							ALTER TABLE bms.auth_user ADD CONSTRAINT auth_user_display_name_check
							CHECK (btrim(display_name) <> '');
						END IF;
					END $$
					""");
			execute(statement, """
					CREATE TABLE IF NOT EXISTS bms.auth_user_role (
						user_id UUID NOT NULL REFERENCES bms.auth_user(id) ON DELETE CASCADE,
						role VARCHAR(64) NOT NULL,
						PRIMARY KEY (user_id, role)
					)
					""");
			execute(statement, """
					CREATE TABLE IF NOT EXISTS bms.auth_session (
						token_hash BYTEA PRIMARY KEY,
						user_id UUID NOT NULL REFERENCES bms.auth_user(id) ON DELETE CASCADE,
						created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
						expires_at TIMESTAMPTZ NOT NULL,
						last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
						revoked_at TIMESTAMPTZ
					)
					""");
			execute(statement, """
					CREATE TABLE IF NOT EXISTS bms.auth_audit (
						id BIGSERIAL PRIMARY KEY,
						user_id UUID,
						event_type VARCHAR(64) NOT NULL,
						detail VARCHAR(512),
						created_at TIMESTAMPTZ NOT NULL DEFAULT now()
					)
					""");
			execute(statement, """
					CREATE TABLE IF NOT EXISTS bms.bms_string (
						id UUID PRIMARY KEY,
						string_key VARCHAR(80) NOT NULL UNIQUE,
						display_name VARCHAR(160) NOT NULL,
						cell_count INTEGER NOT NULL CHECK (cell_count > 0),
						brand VARCHAR(160), model VARCHAR(160), capacity_ah NUMERIC,
						created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
						updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
					)
					""");
			execute(statement, "INSERT INTO bms.schema_version(version) VALUES (1) ON CONFLICT DO NOTHING");
			connection.commit();
		} catch (SQLException e) {
			throw e;
		}
	}

	@FunctionalInterface
	private interface ConnectionSource {
		Connection getConnection() throws SQLException;
	}

	private static void execute(Statement statement, String sql) throws SQLException {
		statement.execute(sql);
	}
}
