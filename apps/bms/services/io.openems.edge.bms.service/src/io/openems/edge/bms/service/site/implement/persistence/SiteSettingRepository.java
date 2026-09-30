package io.openems.edge.bms.service.site.implement.persistence;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public interface SiteSettingRepository {

	Optional<String> find(String key) throws SQLException;

	void save(String key, String value, UUID updatedBy) throws SQLException;
}
