package io.openems.edge.bms.service.strings.implement.persistence;

import java.sql.SQLException;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/**
 * Owns the PostgreSQL business schema needed by the string feature only.
 *
 * <p>It deliberately does not model TA, TC or bridge component properties.
	 * Those remain native ConfigurationAdmin records managed by the core
	 * {@code BmsStringLifecycle}.</p>
 */
@Component(name = "io.openems.edge.bms.platform.strings.StringSchemaBootstrap", immediate = true)
public class StringSchemaBootstrap {

	@Reference
	private BmsDatabase database;

	@Activate
	protected void activate() throws SQLException {
		if (!this.database.isAvailable()) {
			return;
		}
		try (var connection = this.database.getConnection(); var statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			try {
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS string_index INTEGER");
				statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS bms_string_index_unique ON bms.bms_string(string_index)");
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS bridge_id VARCHAR(128)");
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS start_ta_unit_id INTEGER");
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS cutoff_voltage NUMERIC");
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS float_voltage NUMERIC");
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS boost_voltage NUMERIC");
				statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS rated_resistance_uohm NUMERIC");
				statement.execute("""
						CREATE TABLE IF NOT EXISTS bms.string_threshold (
							string_id UUID PRIMARY KEY REFERENCES bms.bms_string(id) ON DELETE CASCADE,
							low_voltage NUMERIC, high_voltage NUMERIC, high_temperature NUMERIC,
							high_resistance NUMERIC, rated_resistance_uohm NUMERIC,
							updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
						)
						""");
				statement.execute("INSERT INTO bms.schema_version(version) VALUES (3) ON CONFLICT DO NOTHING");
				connection.commit();
			} catch (SQLException e) {
				connection.rollback();
				throw e;
			}
		}
	}
}
