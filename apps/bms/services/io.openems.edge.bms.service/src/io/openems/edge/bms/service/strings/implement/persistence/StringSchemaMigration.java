package io.openems.edge.bms.service.strings.implement.persistence;

import java.sql.SQLException;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Adds only string business fields; runtime component properties stay in ConfigurationAdmin. */
@Component(name = "io.openems.edge.bms.platform.strings.StringSchemaMigration", immediate = true)
public class StringSchemaMigration {

	@Reference
	private BmsDatabase database;

	@Activate
	protected void activate() throws SQLException {
		if (!this.database.isAvailable()) return;
		try (var connection = this.database.getConnection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS string_index INTEGER");
			statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS bms_string_index_unique ON bms.bms_string(string_index)");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS bridge_id VARCHAR(128)");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS start_ta_unit_id INTEGER");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS cutoff_voltage NUMERIC");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS float_voltage NUMERIC");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS boost_voltage NUMERIC");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS rated_resistance_uohm NUMERIC");
			statement.execute("ALTER TABLE bms.bms_string ADD COLUMN IF NOT EXISTS soc_checkpoint_policy VARCHAR(32) NOT NULL DEFAULT 'DISABLED'");
		}
	}
}
