package io.openems.edge.bms.service.site.implement.persistence;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

@Component(name = "io.openems.edge.bms.platform.site.PostgresSiteSettingRepository", service = SiteSettingRepository.class)
public class PostgresSiteSettingRepository implements SiteSettingRepository {

	@Reference
	private BmsDatabase database;

	@Override
	public Optional<String> find(String key) throws SQLException {
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement(
						"SELECT setting_value #>> '{}' FROM bms.site_setting WHERE setting_key = ?")) {
			statement.setString(1, key);
			try (var result = statement.executeQuery()) {
				return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
			}
		}
	}

	@Override
	public void save(String key, String value, UUID updatedBy) throws SQLException {
		try (var connection = this.database.getConnection();
				var statement = connection.prepareStatement("""
					INSERT INTO bms.site_setting(setting_key, setting_value, updated_at, updated_by)
					VALUES (?, to_jsonb(CAST(? AS text)), now(), ?)
					ON CONFLICT (setting_key) DO UPDATE SET
						setting_value = EXCLUDED.setting_value,
						updated_at = EXCLUDED.updated_at,
						updated_by = EXCLUDED.updated_by
					""")) {
			statement.setString(1, key);
			statement.setString(2, value);
			statement.setObject(3, updatedBy, Types.OTHER);
			statement.executeUpdate();
		}
	}
}
