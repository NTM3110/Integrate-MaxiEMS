package io.openems.edge.bms.service.persistence.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.google.gson.JsonObject;

import io.openems.edge.bms.service.assets.implement.PostgresAssetManagementService;

/** Runs only against a disposable PostgreSQL database. */
public class AssetManagementPostgresTest {
	private PGSimpleDataSource source;
	private PostgresAssetManagementService assets;
	private boolean disposable;
	private UUID actor;

	@Before
	public void setUp() throws Exception {
		var url = System.getenv("BMS_TEST_POSTGRES_URL");
		Assume.assumeTrue("BMS_TEST_POSTGRES_URL is not configured", url != null && !url.isBlank());
		this.source = new PGSimpleDataSource();
		this.source.setUrl(url);
		this.source.setUser(environment("BMS_TEST_POSTGRES_USER", "postgres"));
		this.source.setPassword(environment("BMS_TEST_POSTGRES_PASSWORD", "postgres"));
		try (var connection = this.source.getConnection(); var query = connection.createStatement();
				var rows = query.executeQuery("SELECT current_database()")) {
			rows.next();
			Assume.assumeFalse(Set.of("maxiems", "demo", "postgres", "template0", "template1")
					.contains(rows.getString(1).toLowerCase(java.util.Locale.ROOT)));
			this.disposable = true;
		}
		try (var connection = this.source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
		BmsSchema.migrate(this.source);
		FeatureSchemaMigration.migrate(new Database(this.source));
		this.actor = UUID.randomUUID();
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement("""
				INSERT INTO bms.auth_user(id,username,display_name,password_hash,salt)
				VALUES (?,?,'Tester','x'::bytea,'y'::bytea)
				""")) {
			statement.setObject(1, this.actor);
			statement.setString(2, "asset-test");
			statement.executeUpdate();
		}
		this.assets = new PostgresAssetManagementService();
		var field = PostgresAssetManagementService.class.getDeclaredField("database");
		field.setAccessible(true);
		field.set(this.assets, new Database(this.source));
	}

	@After
	public void tearDown() throws Exception {
		if (!this.disposable) return;
		try (var connection = this.source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS bms CASCADE");
		}
	}

	@Test
	public void emptySaveReloadAndRollback() throws Exception {
		assertTrue(this.assets.getSite().get("profile").isJsonNull());
		var stringId = UUID.randomUUID();
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement("""
				INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count)
				VALUES (?,1,'str1','String 1',2)
				""")) {
			statement.setObject(1, stringId);
			statement.executeUpdate();
		}
		assertTrue(this.assets.getString(1).get("profile").isJsonNull());
		assertThrows(IllegalArgumentException.class, () -> this.assets.save(site("Rejected"), 2, device(), this.actor));
		assertTrue(this.assets.getSite().get("profile").isJsonNull());
		var firstDevice = device();
		firstDevice.addProperty("normalVoltage", "54.60");
		firstDevice.addProperty("currentLimit", "12.50");
		firstDevice.addProperty("manufacturingDate", "2025-12-31");
		this.assets.save(site("Owner A"), 1, firstDevice, this.actor);
		assertEquals("Owner A", this.assets.getSite().getAsJsonObject("profile").get("owner").getAsString());
		assertEquals("Vendor A", this.assets.getString(1).getAsJsonObject("profile").get("manufacturer").getAsString());
		assertEquals("2026-01-01", this.assets.getString(1).getAsJsonObject("profile").get("installationDate").getAsString());
		assertEquals("80", this.assets.getString(1).getAsJsonObject("profile").get("sohReplaceThreshold").getAsString());
		assertEquals("54.60", this.assets.getString(1).getAsJsonObject("profile").get("normalVoltage").getAsString());
		assertEquals("12.50", this.assets.getString(1).getAsJsonObject("profile").get("currentLimit").getAsString());
		assertEquals("2025-12-31", this.assets.getString(1).getAsJsonObject("profile").get("manufacturingDate").getAsString());
		var secondStringId = UUID.randomUUID();
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement("""
				INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count)
				VALUES (?,2,'str2','String 2',2)
				""")) {
			statement.setObject(1, secondStringId);
			statement.executeUpdate();
		}
		var secondDevice = device();
		secondDevice.addProperty("manufacturer", "Vendor B");
		this.assets.save(site("Owner B"), 2, secondDevice, this.actor);
		assertEquals("Vendor A", this.assets.getString(1).getAsJsonObject("profile").get("manufacturer").getAsString());
		assertEquals("Vendor B", this.assets.getString(2).getAsJsonObject("profile").get("manufacturer").getAsString());
		assertEquals(0, this.assets.listSohHistory(1, null, null, 100).getAsJsonArray("points").size());
		UUID assetId;
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement(
				"SELECT id FROM bms.asset WHERE string_id=?")) {
			statement.setObject(1, stringId);
			try (var rows = statement.executeQuery()) { rows.next(); assetId = (UUID) rows.getObject(1); }
		}
		try (var connection = this.source.getConnection()) {
			insertHistory(connection, assetId, "event-1");
			assertThrows(SQLException.class, () -> insertHistory(connection, assetId, "event-1"));
		}
		assertEquals(1, this.assets.listSohHistory(1, null, null, 100).getAsJsonArray("points").size());
		FeatureSchemaMigration.migrate(new Database(this.source));
		assertEquals("Owner B", this.assets.getSite().getAsJsonObject("profile").get("owner").getAsString());
		try (var connection = this.source.getConnection(); var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT count(*) FROM bms.asset WHERE string_id='" + stringId + "'")) {
			rows.next();
			assertEquals(1, rows.getInt(1));
		}
	}

	@Test
	public void migratesLegacyJsonOnceAndKeepsUnknownMetadata() throws Exception {
		var stringId = UUID.randomUUID();
		var assetId = UUID.randomUUID();
		try (var connection = this.source.getConnection();
				var string = connection.prepareStatement("""
					INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count)
					VALUES (?,1,'str1','String 1',2)
					""");
				var site = connection.prepareStatement("""
					INSERT INTO bms.site_setting(setting_key,setting_value,updated_by)
					VALUES ('asset_management.site_profile.v1',CAST(? AS jsonb),?)
					""");
				var asset = connection.prepareStatement("""
					INSERT INTO bms.asset(id,string_id,asset_type,metadata)
					VALUES (?,?,'BMS_STRING_PROFILE',CAST(? AS jsonb))
					""")) {
			string.setObject(1, stringId);
			string.executeUpdate();
			site.setString(1, "{\"owner\":\"Legacy owner\",\"maxicomSerial\":\"SN-5\"}");
			site.setObject(2, this.actor);
			site.executeUpdate();
			asset.setObject(1, assetId);
			asset.setObject(2, stringId);
			asset.setString(3, "{\"dcLevel\":\"48V\",\"normalVoltage\":\"54.6\","
					+ "\"commissioningDate\":\"2025-04-12\",\"unknownLegacy\":\"keep\"}");
			asset.executeUpdate();
		}
		FeatureSchemaMigration.migrate(new Database(this.source));
		assertEquals("Legacy owner", this.assets.getSite().getAsJsonObject("profile").get("owner").getAsString());
		var profile = this.assets.getString(1).getAsJsonObject("profile");
		assertEquals("48V", profile.get("dcLevel").getAsString());
		assertEquals("54.6", profile.get("normalVoltage").getAsString());
		assertEquals("2025-04-12", profile.get("commissioningDate").getAsString());
		assertEquals(20, profile.size());
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement(
				"SELECT metadata->>'unknownLegacy',normal_voltage,jsonb_exists(metadata,'dcLevel'),jsonb_exists(metadata,'normalVoltage') FROM bms.asset WHERE id=?")) {
			statement.setObject(1, assetId);
			try (var rows = statement.executeQuery()) {
				rows.next();
				assertEquals("keep", rows.getString(1));
				assertEquals("54.6", rows.getBigDecimal(2).toPlainString());
				assertTrue(!rows.getBoolean(3));
				assertTrue(!rows.getBoolean(4));
			}
		}
		try (var connection = this.source.getConnection(); var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT count(*) FROM bms.site_setting WHERE setting_key='asset_management.site_profile.v1'")) {
			rows.next();
			assertEquals(0, rows.getInt(1));
		}
		// A relational edit must win over the retained legacy setting on restart.
		this.assets.save(site("New owner"), null, null, this.actor);
		FeatureSchemaMigration.migrate(new Database(this.source));
		assertEquals("New owner", this.assets.getSite().getAsJsonObject("profile").get("owner").getAsString());
		// Clearing a value removes its old JSON key so a repeated migration cannot
		// restore it. Unrecognized metadata remains preserved.
		var cleared = device();
		cleared.addProperty("dcLevel", "");
		this.assets.save(site("New owner"), 1, cleared, this.actor);
		FeatureSchemaMigration.migrate(new Database(this.source));
		assertEquals("", this.assets.getString(1).getAsJsonObject("profile").get("dcLevel").getAsString());
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement(
				"SELECT metadata->>'unknownLegacy',jsonb_exists(metadata,'dcLevel') FROM bms.asset WHERE id=?")) {
			statement.setObject(1, assetId);
			try (var rows = statement.executeQuery()) {
				rows.next();
				assertEquals("keep", rows.getString(1));
				assertTrue(!rows.getBoolean(2));
			}
		}
	}

	@Test
	public void migrationRetainsMalformedAndConflictingLegacyValues() throws Exception {
		var stringId = UUID.randomUUID();
		var assetId = UUID.randomUUID();
		try (var connection = this.source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("INSERT INTO bms.site_setting(setting_key,setting_value) VALUES "
					+ "('asset_management.site_profile.v1','{\"owner\":42}'::jsonb)");
		}
		FeatureSchemaMigration.migrate(new Database(this.source));
		assertTrue(this.assets.getSite().get("profile").isJsonNull());
		try (var connection = this.source.getConnection(); var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT setting_value->>'owner' FROM bms.site_setting "
						+ "WHERE setting_key='asset_management.site_profile.v1'")) {
			rows.next();
			assertEquals("42", rows.getString(1));
		}
		try (var connection = this.source.getConnection();
				var string = connection.prepareStatement("""
					INSERT INTO bms.bms_string(id,string_index,string_key,display_name,cell_count)
					VALUES (?,1,'str1','String 1',2)
					""");
				var asset = connection.prepareStatement("""
					INSERT INTO bms.asset(id,string_id,asset_type,dc_level,metadata)
					VALUES (?,?,'BMS_STRING_PROFILE','new',CAST(? AS jsonb))
					""")) {
			string.setObject(1, stringId);
			string.executeUpdate();
			asset.setObject(1, assetId);
			asset.setObject(2, stringId);
			asset.setString(3, "{\"dcLevel\":\"old\",\"normalVoltage\":\"bad\","
					+ "\"currentLimit\":\"12.50\",\"unknownLegacy\":{\"keep\":true}}");
			asset.executeUpdate();
		}
		this.assets.save(site("Relational owner"), null, null, this.actor);
		try (var connection = this.source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("UPDATE bms.site_setting SET setting_value='{\"owner\":\"Legacy owner\"}'::jsonb "
					+ "WHERE setting_key='asset_management.site_profile.v1'");
		}
		FeatureSchemaMigration.migrate(new Database(this.source));
		FeatureSchemaMigration.migrate(new Database(this.source));
		assertEquals("Relational owner", this.assets.getSite().getAsJsonObject("profile").get("owner").getAsString());
		assertEquals("new", this.assets.getString(1).getAsJsonObject("profile").get("dcLevel").getAsString());
		assertEquals("12.50", this.assets.getString(1).getAsJsonObject("profile").get("currentLimit").getAsString());
		try (var connection = this.source.getConnection(); var statement = connection.prepareStatement("""
				SELECT metadata->>'dcLevel',metadata->>'normalVoltage',metadata->'unknownLegacy',
					jsonb_exists(metadata,'currentLimit') FROM bms.asset WHERE id=?
				""")) {
			statement.setObject(1, assetId);
			try (var rows = statement.executeQuery()) {
				rows.next();
				assertEquals("old", rows.getString(1));
				assertEquals("bad", rows.getString(2));
				assertEquals("{\"keep\": true}", rows.getString(3));
				assertTrue(!rows.getBoolean(4));
			}
		}
		try (var connection = this.source.getConnection(); var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT setting_value->>'owner' FROM bms.site_setting "
						+ "WHERE setting_key='asset_management.site_profile.v1'")) {
			rows.next();
			assertEquals("Legacy owner", rows.getString(1));
		}
	}

	private static void insertHistory(Connection connection, UUID assetId, String sourceKey) throws SQLException {
		try (var statement = connection.prepareStatement("""
				INSERT INTO bms.asset_soh_history(asset_id,soh,measured_at,source,source_key)
				VALUES (?,88,now(),'CALIBRATION',?)
				""")) {
			statement.setObject(1, assetId);
			statement.setString(2, sourceKey);
			statement.executeUpdate();
		}
	}

	private static JsonObject site(String owner) {
		var result = new JsonObject();
		result.addProperty("owner", owner);
		for (var key : new String[] { "operationalTeam", "substation", "coordinates", "maxicomSerial" }) result.addProperty(key, "");
		return result;
	}

	private static JsonObject device() {
		var result = new JsonObject();
		for (var key : new String[] { "dcLevel", "batteryBankId", "manufacturer", "technology", "normalVoltage",
				"batchLotNumber", "manufacturingDate", "commissioningDate", "installationDate", "responsiblePerson",
				"sensorMapping", "sensorCalibrationStatus", "currentLimit", "testOcv", "testFloatVoltage",
				"testIr", "testNegativeTerminalTemp", "sohWarningThreshold", "sohReplaceThreshold", "replacementDate" }) {
			result.addProperty(key, "");
		}
		result.addProperty("manufacturer", "Vendor A");
		result.addProperty("installationDate", "2026-01-01");
		result.addProperty("sohReplaceThreshold", "80");
		return result;
	}

	private static String environment(String key, String fallback) {
		var value = System.getenv(key);
		return value == null ? fallback : value;
	}

	private record Database(PGSimpleDataSource source) implements BmsDatabase {
		@Override public Connection getConnection() throws SQLException { return this.source.getConnection(); }
		@Override public boolean isAvailable() { return true; }
	}
}
