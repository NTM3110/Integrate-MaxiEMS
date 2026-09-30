package io.openems.edge.bms.service.assets.implement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.openems.edge.bms.service.assets.contracts.AssetManagementService;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** One transaction for the site and selected string profile. */
@Component(name = "io.openems.edge.bms.platform.assets.PostgresAssetManagementService", service = AssetManagementService.class)
public class PostgresAssetManagementService implements AssetManagementService {
	private static final String TYPE = "BMS_STRING_PROFILE";
	private static final String[] SITE_FIELDS = { "owner", "operationalTeam", "substation", "coordinates",
			"maxicomSerial" };
	private static final String[] STRING_FIELDS = { "dcLevel", "batteryBankId", "manufacturer", "technology",
			"normalVoltage", "batchLotNumber", "manufacturingDate", "commissioningDate", "installationDate",
			"responsiblePerson", "sensorMapping", "sensorCalibrationStatus", "currentLimit", "testOcv",
			"testFloatVoltage", "testIr", "testNegativeTerminalTemp", "sohWarningThreshold",
			"sohReplaceThreshold", "replacementDate" };
	private static final String[] NUMERIC_FIELDS = { "normalVoltage", "currentLimit", "testOcv", "testFloatVoltage",
			"testIr", "testNegativeTerminalTemp", "sohWarningThreshold", "sohReplaceThreshold" };
	private static final String[] DATE_FIELDS = { "manufacturingDate", "commissioningDate", "installationDate",
			"replacementDate" };
	private static final String[] STRING_COLUMNS = { "dc_level", "battery_bank_id", "manufacturer", "technology",
			"normal_voltage", "batch_lot_number", "manufacture_date", "commissioning_date",
			"installation_date", "responsible_person", "sensor_mapping", "sensor_calibration_status",
			"current_limit", "test_ocv", "test_float_voltage", "test_ir", "test_negative_terminal_temp",
			"soh_warning_threshold", "replacement_soh_threshold", "replacement_date" };

	@Reference
	private BmsDatabase database;

	@Override
	public JsonObject getSite() throws SQLException {
		try (var connection = this.database.getConnection()) {
			return site(connection);
		}
	}

	@Override
	public JsonObject getString(int stringIndex) throws SQLException {
		try (var connection = this.database.getConnection()) {
			return string(connection, stringIndex);
		}
	}

	@Override
	public JsonObject save(JsonObject siteProfile, Integer stringIndex, JsonObject stringProfile, UUID updatedBy)
			throws SQLException {
		var site = validate(siteProfile, SITE_FIELDS);
		var device = stringIndex == null ? null : validate(stringProfile, STRING_FIELDS);
		if (stringIndex != null && stringIndex < 1) throw new IllegalArgumentException("Invalid stringIndex");
		if (stringIndex != null && stringProfile == null) throw new IllegalArgumentException("stringProfile is required");
		if (stringIndex == null && stringProfile != null) throw new IllegalArgumentException("stringIndex is required");
		if (device != null) {
			for (var field : NUMERIC_FIELDS) numeric(device, field);
			for (var field : DATE_FIELDS) date(device, field);
			var warning = numeric(device, "sohWarningThreshold");
			var replacement = numeric(device, "sohReplaceThreshold");
			if (warning != null && (warning < 0 || warning > 100)
					|| replacement != null && (replacement < 0 || replacement > 100)) {
				throw new IllegalArgumentException("SoH thresholds must be between 0 and 100");
			}
		}
		try (var connection = this.database.getConnection()) {
			connection.setAutoCommit(false);
			try {
				UUID stringId = stringIndex == null ? null : findStringId(connection, stringIndex);
				try (var statement = connection.prepareStatement("""
						INSERT INTO bms.asset_site_profile(id,owner,operational_team,substation,coordinates,maxicom_serial,updated_at,updated_by)
						VALUES (1,?,?,?,?,?,now(),?)
						ON CONFLICT (id) DO UPDATE SET owner=EXCLUDED.owner,
						operational_team=EXCLUDED.operational_team,substation=EXCLUDED.substation,
						coordinates=EXCLUDED.coordinates,maxicom_serial=EXCLUDED.maxicom_serial,
						updated_at=EXCLUDED.updated_at,updated_by=EXCLUDED.updated_by
						""")) {
					for (int i = 0; i < SITE_FIELDS.length; i++) statement.setString(i + 1, nullable(site, SITE_FIELDS[i]));
					statement.setObject(6, updatedBy);
					statement.executeUpdate();
				}
				if (device != null) upsertAsset(connection, stringId, device);
				var result = site(connection);
				if (device != null) {
					var saved = string(connection, stringIndex);
					result.add("stringProfile", saved.get("profile"));
					result.add("stringId", saved.get("stringId"));
				}
				result.add("siteProfile", result.remove("profile"));
				connection.commit();
				return result;
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			}
		}
	}

	@Override
	public JsonObject listSohHistory(int stringIndex, Instant from, Instant to, int limit) throws SQLException {
		if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
		if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must not exceed to");
		try (var connection = this.database.getConnection()) {
			var stringId = findStringId(connection, stringIndex);
			var points = new JsonArray();
			try (var statement = connection.prepareStatement("""
					SELECT h.soh,h.measured_at,h.source FROM bms.asset_soh_history h
					JOIN bms.asset a ON a.id=h.asset_id
					WHERE a.string_id=? AND a.asset_type=?
					AND (CAST(? AS timestamptz) IS NULL OR h.measured_at >= ?)
					AND (CAST(? AS timestamptz) IS NULL OR h.measured_at <= ?)
					ORDER BY h.measured_at DESC,h.id DESC LIMIT ?
					""")) {
				statement.setObject(1, stringId);
				statement.setString(2, TYPE);
				for (int parameter : new int[] { 3, 4 }) {
					if (from == null) statement.setNull(parameter, Types.TIMESTAMP_WITH_TIMEZONE);
					else statement.setTimestamp(parameter, Timestamp.from(from));
				}
				for (int parameter : new int[] { 5, 6 }) {
					if (to == null) statement.setNull(parameter, Types.TIMESTAMP_WITH_TIMEZONE);
					else statement.setTimestamp(parameter, Timestamp.from(to));
				}
				statement.setInt(7, limit);
				try (var rows = statement.executeQuery()) {
					while (rows.next()) {
						var point = new JsonObject();
						point.addProperty("soh", rows.getBigDecimal(1));
						point.addProperty("measuredAt", rows.getTimestamp(2).toInstant().toString());
						point.addProperty("source", rows.getString(3));
						points.add(point);
					}
				}
			}
			var result = new JsonObject();
			result.add("points", points);
			return result;
		}
	}

	private static JsonObject site(Connection connection) throws SQLException {
		var result = new JsonObject();
		result.add("profile", JsonNull.INSTANCE);
		result.add("updatedAt", JsonNull.INSTANCE);
		try (var statement = connection.prepareStatement("""
				SELECT owner,operational_team,substation,coordinates,maxicom_serial,updated_at
				FROM bms.asset_site_profile WHERE id=1
				""")) {
			try (var rows = statement.executeQuery()) {
				if (rows.next()) {
					var profile = new JsonObject();
					for (int i = 0; i < SITE_FIELDS.length; i++) addString(profile, SITE_FIELDS[i], rows.getString(i + 1));
					result.add("profile", profile);
					result.addProperty("updatedAt", rows.getTimestamp(6).toInstant().toString());
				}
			}
		}
		return result;
	}

	private static JsonObject string(Connection connection, int stringIndex) throws SQLException {
		var stringId = findStringId(connection, stringIndex);
		var result = new JsonObject();
		result.addProperty("stringId", stringId.toString());
		result.add("profile", JsonNull.INSTANCE);
		result.add("updatedAt", JsonNull.INSTANCE);
		var query = "SELECT " + String.join(",", STRING_COLUMNS)
				+ ",updated_at FROM bms.asset WHERE string_id=? AND asset_type=?";
		try (var statement = connection.prepareStatement(query)) {
			statement.setObject(1, stringId);
			statement.setString(2, TYPE);
			try (var rows = statement.executeQuery()) {
				if (rows.next()) {
					var profile = new JsonObject();
					for (int i = 0; i < STRING_FIELDS.length; i++) {
						var value = rows.getObject(i + 1);
						addString(profile, STRING_FIELDS[i], value instanceof BigDecimal decimal
								? decimal.toPlainString() : value == null ? null : value.toString());
					}
					result.add("profile", profile);
					result.addProperty("updatedAt", rows.getTimestamp(STRING_FIELDS.length + 1).toInstant().toString());
				}
			}
		}
		return result;
	}

	private static void addString(JsonObject json, String key, String value) {
		json.addProperty(key, value == null ? "" : value);
	}

	private static UUID findStringId(Connection connection, int stringIndex) throws SQLException {
		if (stringIndex < 1) throw new IllegalArgumentException("Invalid stringIndex");
		try (var statement = connection.prepareStatement("SELECT id FROM bms.bms_string WHERE string_index=?")) {
			statement.setInt(1, stringIndex);
			try (var rows = statement.executeQuery()) {
				if (rows.next()) return (UUID) rows.getObject(1);
			}
		}
		throw new IllegalArgumentException("Battery string does not exist");
	}

	private static void upsertAsset(Connection connection, UUID stringId, JsonObject profile) throws SQLException {
		var columns = String.join(",", STRING_COLUMNS);
		var placeholders = String.join(",", java.util.Collections.nCopies(STRING_FIELDS.length, "?"));
		var changes = new StringBuilder();
		for (var column : STRING_COLUMNS) {
			if (!changes.isEmpty()) changes.append(',');
			changes.append(column).append("=EXCLUDED.").append(column);
		}
		// Clear only legacy keys for fields now owned by columns. Unknown metadata
		// remains untouched, and a subsequent migration cannot restore a field the
		// user intentionally cleared.
		var legacyKeys = "ARRAY['" + String.join("','", STRING_FIELDS) + "']";
		var sql = "INSERT INTO bms.asset(id,string_id,asset_type," + columns + ") VALUES (?,?,?," + placeholders
				+ ") ON CONFLICT (string_id) WHERE asset_type='BMS_STRING_PROFILE' AND string_id IS NOT NULL "
				+ "DO UPDATE SET " + changes + ",metadata=bms.asset.metadata - " + legacyKeys + ",updated_at=now()";
		try (var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, stringId);
			statement.setString(3, TYPE);
			for (int i = 0; i < STRING_FIELDS.length; i++) {
				var field = STRING_FIELDS[i];
				int parameter = i + 4;
				if (isDate(field)) {
					var value = date(profile, field);
					if (value == null) statement.setNull(parameter, Types.DATE);
					else statement.setDate(parameter, Date.valueOf(value));
				} else if (isNumeric(field)) {
					var value = nullable(profile, field);
					if (value == null) statement.setNull(parameter, Types.NUMERIC);
					else statement.setBigDecimal(parameter, new BigDecimal(value));
				} else statement.setString(parameter, nullable(profile, field));
			}
			statement.executeUpdate();
		}
	}

	private static boolean isDate(String field) {
		for (var value : DATE_FIELDS) if (value.equals(field)) return true;
		return false;
	}

	private static boolean isNumeric(String field) {
		for (var value : NUMERIC_FIELDS) if (value.equals(field)) return true;
		return false;
	}

	private static JsonObject validate(JsonObject input, String[] allowed) {
		if (input == null) throw new IllegalArgumentException("Profile must be an object");
		var normalized = new JsonObject();
		for (var entry : input.entrySet()) {
			boolean known = false;
			for (var field : allowed) if (field.equals(entry.getKey())) known = true;
			if (!known) throw new IllegalArgumentException("Unknown profile field: " + entry.getKey());
		}
		for (var field : allowed) {
			var value = input.get(field);
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
				throw new IllegalArgumentException(field + " must be a string");
			var text = value.getAsString().trim();
			int maxLength = "manufacturer".equals(field) ? 160 : 512;
			if (text.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
			normalized.addProperty(field, text);
		}
		return normalized;
	}

	private static String nullable(JsonObject profile, String field) {
		var value = profile.get(field).getAsString();
		return value.isBlank() ? null : value;
	}

	private static Double numeric(JsonObject profile, String field) {
		var value = nullable(profile, field);
		if (value == null) return null;
		try {
			var number = Double.parseDouble(value);
			if (!Double.isFinite(number)) throw new NumberFormatException();
			return number;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(field + " must be a finite number");
		}
	}

	private static LocalDate date(JsonObject profile, String field) {
		var value = nullable(profile, field);
		if (value == null) return null;
		try {
			return LocalDate.parse(value);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(field + " must be an ISO date");
		}
	}
}
