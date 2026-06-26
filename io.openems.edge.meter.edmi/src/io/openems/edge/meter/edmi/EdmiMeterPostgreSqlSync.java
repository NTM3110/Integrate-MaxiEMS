package io.openems.edge.meter.edmi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL sync service for EDMI meter components.
 * Automatically creates/updates/deletes meter records in PostgreSQL
 * when OpenEMS components are activated/deactivated.
 */
public class EdmiMeterPostgreSqlSync {

	private final Logger log = LoggerFactory.getLogger(EdmiMeterPostgreSqlSync.class);

	private final String url;
	private final String user;
	private final String password;
	private final boolean enabled;

	public EdmiMeterPostgreSqlSync(String host, int port, String database, 
			String user, String password, boolean enabled) {
		this.enabled = enabled;
		this.url = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
		this.user = user;
		this.password = password;
		
		if (!enabled) {
			return;
		}
		
		// Test connection
		try {
			Connection conn = this.getConnection();
			conn.close();
			this.log.info("PostgreSQL sync service initialized");
		} catch (SQLException e) {
			this.log.error("Failed to connect to PostgreSQL: {}", e.getMessage());
		}
	}

	private Connection getConnection() throws SQLException {
		return DriverManager.getConnection(this.url, this.user, this.password);
	}

	/**
	 * Create or update a meter record in PostgreSQL when component is activated.
	 */
	public void syncMeter(String componentId, int serialNumber, String username, 
			String password, String energyRole, String energySourceType, 
			String meterName, boolean enabled) {
		if (!this.enabled) {
			return;
		}
		
		// First, ensure the role exists
		ensureRoleExists(energyRole);
		
		// Then ensure the source type exists (if not NONE)
		if (!"NONE".equals(energySourceType)) {
			ensureSourceExists(energySourceType);
		}
		
		// Insert or update the meter
		String sql = "INSERT INTO meters (serial_number, username, password, meter_name, " +
				"role_id, source_id, type, model, enabled, created_at) " +
				"VALUES (?, ?, ?, ?, " +
				"(SELECT id FROM energy_roles WHERE name = ?), " +
				"(SELECT id FROM energy_sources WHERE name = ?), " +
				"'EDMI', 'MK10', ?, NOW()) " +
				"ON CONFLICT (serial_number) DO UPDATE SET " +
				"username = EXCLUDED.username, " +
				"password = EXCLUDED.password, " +
				"meter_name = EXCLUDED.meter_name, " +
				"role_id = EXCLUDED.role_id, " +
				"source_id = EXCLUDED.source_id, " +
				"enabled = EXCLUDED.enabled, " +
				"updated_at = NOW()";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, serialNumber);
			stmt.setString(2, username);
			stmt.setString(3, password);
			stmt.setString(4, meterName);
			stmt.setString(5, energyRole);
			stmt.setString(6, "NONE".equals(energySourceType) ? null : energySourceType);
			stmt.setBoolean(7, enabled);
			
			stmt.executeUpdate();
			this.log.info("Synced meter [{}] to PostgreSQL", componentId);
			
		} catch (SQLException e) {
			this.log.error("Failed to sync meter [{}]: {}", componentId, e.getMessage());
		}
	}

	/**
	 * Delete or disable a meter record in PostgreSQL when component is deactivated.
	 */
	public void deleteMeter(String componentId, int serialNumber) {
		if (!this.enabled) {
			return;
		}
		
		// Option 1: Soft delete (set enabled = false)
		String sql = "UPDATE meters SET enabled = false, updated_at = NOW() WHERE serial_number = ?";
		
		// Option 2: Hard delete (uncomment if preferred)
		// String sql = "DELETE FROM meters WHERE serial_number = ?";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setInt(1, serialNumber);
			stmt.executeUpdate();
			this.log.info("Disabled meter [{}] in PostgreSQL", componentId);
			
		} catch (SQLException e) {
			this.log.error("Failed to delete meter [{}]: {}", componentId, e.getMessage());
		}
	}

	/**
	 * Ensure the energy role exists in the database.
	 */
	private void ensureRoleExists(String roleName) {
		String sql = "INSERT INTO energy_roles (name, description) VALUES (?, ?) " +
				"ON CONFLICT (name) DO NOTHING";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, roleName);
			stmt.setString(2, "Auto-created from EDMI meter component");
			stmt.executeUpdate();
			
		} catch (SQLException e) {
			this.log.error("Failed to ensure role exists: {}", e.getMessage());
		}
	}

	/**
	 * Ensure the energy source exists in the database.
	 */
	private void ensureSourceExists(String sourceName) {
		String sql = "INSERT INTO energy_sources (name, description) VALUES (?, ?) " +
				"ON CONFLICT (name) DO NOTHING";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, sourceName);
			stmt.setString(2, "Auto-created from EDMI meter component");
			stmt.executeUpdate();
			
		} catch (SQLException e) {
			this.log.error("Failed to ensure source exists: {}", e.getMessage());
		}
	}
}
