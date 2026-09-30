package io.openems.edge.bms.service.assets.contracts;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;

/** PostgreSQL-backed Asset Management profiles and completed SoH results. */
public interface AssetManagementService {
	JsonObject getSite() throws SQLException;
	JsonObject getString(int stringIndex) throws SQLException;
	JsonObject save(JsonObject siteProfile, Integer stringIndex, JsonObject stringProfile, UUID updatedBy)
			throws SQLException;
	JsonObject listSohHistory(int stringIndex, Instant from, Instant to, int limit) throws SQLException;
}
