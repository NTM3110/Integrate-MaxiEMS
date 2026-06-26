package io.openems.edge.controller.energy.calculator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL connector for storing energy calculation breakdowns.
 * Compatible with standard PostgreSQL (no TimescaleDB extensions required).
 */
public class PostgreSqlConnector {

	private final Logger log = LoggerFactory.getLogger(PostgreSqlConnector.class);

	private final String url;
	private final String user;
	private final String password;
	private final boolean isReadOnly;
	private final String controllerId;

	public PostgreSqlConnector(String controllerId, String host, int port, String database, 
			String user, String password, boolean isReadOnly) {
		this.controllerId = controllerId;
		this.url = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
		this.user = user;
		this.password = password;
		this.isReadOnly = isReadOnly;
		
		// Test connection
		try {
			Connection conn = this.getConnection();
			conn.close();
			this.log.info("PostgreSQL connection successful for controller [{}]", controllerId);
		} catch (SQLException e) {
			this.log.error("Failed to connect to PostgreSQL: {}", e.getMessage());
		}
	}

	private Connection getConnection() throws SQLException {
		return DriverManager.getConnection(this.url, this.user, this.password);
	}

	/**
	 * Write interval state to PostgreSQL.
	 */
	public void writeIntervalState(Instant timestamp, int year, int month, boolean selfAvailable, 
			boolean mainAvailable, boolean backupAvailable, boolean bessAvailable, 
			boolean rtsAvailable, int bessMissingCount, int rtsMissingCount, 
			int mainMissingCount, int backupMissingCount, String scenarioCode) {
		if (this.isReadOnly) {
			return;
		}
		
		String sql = "INSERT INTO interval_state (ts, controller_id, year, month, self_available, " +
				"main_available, backup_available, bess_available, rts_available, " +
				"bess_missing_count, rts_missing_count, main_missing_count, backup_missing_count, scenario_code) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (ts, controller_id) DO UPDATE SET " +
				"year = EXCLUDED.year, month = EXCLUDED.month, " +
				"self_available = EXCLUDED.self_available, main_available = EXCLUDED.main_available, " +
				"backup_available = EXCLUDED.backup_available, " +
				"bess_available = EXCLUDED.bess_available, rts_available = EXCLUDED.rts_available, " +
				"bess_missing_count = EXCLUDED.bess_missing_count, rts_missing_count = EXCLUDED.rts_missing_count, " +
				"main_missing_count = EXCLUDED.main_missing_count, backup_missing_count = EXCLUDED.backup_missing_count, " +
				"scenario_code = EXCLUDED.scenario_code";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setTimestamp(1, Timestamp.from(timestamp));
			stmt.setString(2, this.controllerId);
			stmt.setInt(3, year);
			stmt.setInt(4, month);
			stmt.setBoolean(5, selfAvailable);
			stmt.setBoolean(6, mainAvailable);
			stmt.setBoolean(7, backupAvailable);
			stmt.setBoolean(8, bessAvailable);
			stmt.setBoolean(9, rtsAvailable);
			stmt.setInt(10, bessMissingCount);
			stmt.setInt(11, rtsMissingCount);
			stmt.setInt(12, mainMissingCount);
			stmt.setInt(13, backupMissingCount);
			stmt.setString(14, scenarioCode);
			
			stmt.executeUpdate();
			this.log.debug("Wrote interval state for [{}]", timestamp);
			
		} catch (SQLException e) {
			this.log.error("Failed to write interval state: {}", e.getMessage());
		}
	}

	/**
	 * Write calculation period to PostgreSQL.
	 */
	public void writeCalculationPeriod(Instant periodStart, Instant periodEnd, int year, int month, 
			String scenarioCode, String formulaCode, int intervalCount) {
		if (this.isReadOnly) {
			return;
		}
		
		String sql = "INSERT INTO calculation_period (controller_id, period_start, period_end, year, month, " +
				"scenario_code, formula_code, interval_count) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (controller_id, period_start, period_end) DO UPDATE SET " +
				"year = EXCLUDED.year, month = EXCLUDED.month, " +
				"scenario_code = EXCLUDED.scenario_code, formula_code = EXCLUDED.formula_code, " +
				"interval_count = EXCLUDED.interval_count";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, this.controllerId);
			stmt.setTimestamp(2, Timestamp.from(periodStart));
			stmt.setTimestamp(3, Timestamp.from(periodEnd));
			stmt.setInt(4, year);
			stmt.setInt(5, month);
			stmt.setString(6, scenarioCode);
			stmt.setString(7, formulaCode);
			stmt.setInt(8, intervalCount);
			
			stmt.executeUpdate();
			this.log.debug("Wrote calculation period [{}] to [{}]", periodStart, periodEnd);
			
		} catch (SQLException e) {
			this.log.error("Failed to write calculation period: {}", e.getMessage());
		}
	}

	/**
	 * Write monthly summary to PostgreSQL.
	 */
	public void writeMonthlySummary(int year, int month, double bessToLmvKwh, double rtsToLmvKwh, 
			double totalToLmvKwh, double kFactor, Instant start, Instant end, int inqualifiedIntervals) {
		if (this.isReadOnly) {
			return;
		}
		
		String sql = "INSERT INTO monthly_energy_summary (controller_id, year, month, " +
				"bess_to_lmv_energy_kwh, rts_to_lmv_energy_kwh, total_energy_to_lmv_kwh, k_factor, " +
				"start_date_time, end_date_time, number_of_inqualified_intervals) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (controller_id, year, month) DO UPDATE SET " +
				"bess_to_lmv_energy_kwh = EXCLUDED.bess_to_lmv_energy_kwh, " +
				"rts_to_lmv_energy_kwh = EXCLUDED.rts_to_lmv_energy_kwh, " +
				"total_energy_to_lmv_kwh = EXCLUDED.total_energy_to_lmv_kwh, " +
				"k_factor = EXCLUDED.k_factor, " +
				"start_date_time = EXCLUDED.start_date_time, " +
				"end_date_time = EXCLUDED.end_date_time, " +
				"number_of_inqualified_intervals = EXCLUDED.number_of_inqualified_intervals";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
			
			stmt.setString(1, this.controllerId);
			stmt.setInt(2, year);
			stmt.setInt(3, month);
			stmt.setDouble(4, bessToLmvKwh);
			stmt.setDouble(5, rtsToLmvKwh);
			stmt.setDouble(6, totalToLmvKwh);
			stmt.setDouble(7, kFactor);
			stmt.setTimestamp(8, start != null ? Timestamp.from(start) : null);
			stmt.setTimestamp(9, end != null ? Timestamp.from(end) : null);
			stmt.setInt(10, inqualifiedIntervals);
			
			stmt.executeUpdate();
			
			this.log.info("Wrote monthly summary for {}-{}", year, month);
			
		} catch (SQLException e) {
			this.log.error("Failed to write monthly summary: {}", e.getMessage());
		}
	}

	/**
	 * Write monthly breakdown to PostgreSQL.
	 */
	public void writeMonthlyBreakdown(Instant periodStart, Instant periodEnd, int intervalCount, 
			String scenarioCode, String formulaCode, double bessEnergyKwh, double rtsEnergyKwh, 
			double selfUseEnergyKwh, double gridEnergyKwh, double interconnectEnergyKwh, 
			double kFactor, double rtsToLmvKwh, double bessToLmvKwh) {
		if (this.isReadOnly) {
			return;
		}
		
		String sql = "INSERT INTO monthly_calculation_breakdown (controller_id, " +
				"period_start, period_end, interval_count, scenario_code, formula_code, " +
				"bess_energy_kwh, rts_energy_kwh, self_use_energy_kwh, grid_energy_kwh, " +
				"interconnect_energy_kwh, k_factor, rts_to_lmv_kwh, bess_to_lmv_kwh) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (controller_id, period_start, period_end) DO UPDATE SET " +
				"interval_count = EXCLUDED.interval_count, " +
				"scenario_code = EXCLUDED.scenario_code, formula_code = EXCLUDED.formula_code, " +
				"bess_energy_kwh = EXCLUDED.bess_energy_kwh, rts_energy_kwh = EXCLUDED.rts_energy_kwh, " +
				"self_use_energy_kwh = EXCLUDED.self_use_energy_kwh, grid_energy_kwh = EXCLUDED.grid_energy_kwh, " +
				"interconnect_energy_kwh = EXCLUDED.interconnect_energy_kwh, " +
				"k_factor = EXCLUDED.k_factor, rts_to_lmv_kwh = EXCLUDED.rts_to_lmv_kwh, " +
				"bess_to_lmv_kwh = EXCLUDED.bess_to_lmv_kwh";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, this.controllerId);
			stmt.setTimestamp(2, Timestamp.from(periodStart));
			stmt.setTimestamp(3, Timestamp.from(periodEnd));
			stmt.setInt(4, intervalCount);
			stmt.setString(5, scenarioCode);
			stmt.setString(6, formulaCode);
			stmt.setDouble(7, bessEnergyKwh);
			stmt.setDouble(8, rtsEnergyKwh);
			stmt.setDouble(9, selfUseEnergyKwh);
			stmt.setDouble(10, gridEnergyKwh);
			stmt.setDouble(11, interconnectEnergyKwh);
			stmt.setDouble(12, kFactor);
			stmt.setDouble(13, rtsToLmvKwh);
			stmt.setDouble(14, bessToLmvKwh);
			
			stmt.executeUpdate();
			
		} catch (SQLException e) {
			this.log.error("Failed to write monthly breakdown: {}", e.getMessage());
		}
	}

	/**
	 * Write meter profile reading to PostgreSQL.
	 */
	public void writeMeterProfile(String meterId, Instant timestamp, double recordStatus, 
			double impWh, double expWh, double impVa, double expVa, String meterRole, String sourceType) {
		if (this.isReadOnly) {
			return;
		}
		
		String sql = "INSERT INTO meter_profile (controller_id, meter_id, time_stamp, record_status, " +
				"total_energy_tot_imp_wh, total_energy_tot_exp_wh, total_energy_tot_imp_va, " +
				"total_energy_tot_exp_va, meter_role, source_type) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
				"ON CONFLICT (controller_id, meter_id, time_stamp) DO UPDATE SET " +
				"record_status = EXCLUDED.record_status, " +
				"total_energy_tot_imp_wh = EXCLUDED.total_energy_tot_imp_wh, " +
				"total_energy_tot_exp_wh = EXCLUDED.total_energy_tot_exp_wh, " +
				"total_energy_tot_imp_va = EXCLUDED.total_energy_tot_imp_va, " +
				"total_energy_tot_exp_va = EXCLUDED.total_energy_tot_exp_va, " +
				"meter_role = EXCLUDED.meter_role, source_type = EXCLUDED.source_type";
		
		try (Connection conn = this.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			
			stmt.setString(1, this.controllerId);
			stmt.setString(2, meterId);
			stmt.setTimestamp(3, Timestamp.from(timestamp));
			stmt.setDouble(4, recordStatus);
			stmt.setDouble(5, impWh);
			stmt.setDouble(6, expWh);
			stmt.setDouble(7, impVa);
			stmt.setDouble(8, expVa);
			stmt.setString(9, meterRole);
			stmt.setString(10, sourceType);
			
			stmt.executeUpdate();
			
		} catch (SQLException e) {
			this.log.error("Failed to write meter profile: {}", e.getMessage());
		}
	}

	/**
	 * Deactivate the connector.
	 */
	public void deactivate() {
		this.log.info("PostgreSQL connector deactivated for controller [{}]", this.controllerId);
	}
}