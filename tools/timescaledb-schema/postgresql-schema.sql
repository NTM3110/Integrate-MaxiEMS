-- Standard PostgreSQL Schema for Energy Calculation Reports
-- Compatible with standard PostgreSQL (no TimescaleDB extensions required)

-- ============================================
-- 0. REFERENCE TABLES (Must be created first due to foreign keys)
-- ============================================

-- Energy Roles (SOURCE, SELF_USE, GRID_POINT, INTERCONNECT, etc.)
CREATE TABLE IF NOT EXISTS energy_roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Energy Sources (BESS, RTS, etc.)
CREATE TABLE IF NOT EXISTS energy_sources (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    cost_per_kwh DECIMAL(10, 6) DEFAULT 0.0,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Energy Sites
CREATE TABLE IF NOT EXISTS energy_sites (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Serial Port Configurations
CREATE TABLE IF NOT EXISTS serial_port_configs (
    id SERIAL PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    port VARCHAR(100) NOT NULL,
    baud_rate INTEGER NOT NULL CHECK (baud_rate > 0),
    data_bits INTEGER NOT NULL CHECK (data_bits BETWEEN 5 AND 8),
    stop_bits DECIMAL(2, 1) NOT NULL CHECK (stop_bits IN (1, 1.5, 2)),
    parity VARCHAR(10) NOT NULL CHECK (parity IN ('none', 'even', 'odd', 'mark', 'space')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Users (for meter ownership)
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) UNIQUE,
    full_name VARCHAR(255),
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'viewer',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    last_login TIMESTAMPTZ
);

-- Meters (main configuration table)
CREATE TABLE IF NOT EXISTS meters (
    id SERIAL PRIMARY KEY,
    serial_number INTEGER NOT NULL UNIQUE,
    role_id INTEGER REFERENCES energy_roles(id) ON DELETE SET NULL,
    source_id INTEGER REFERENCES energy_sources(id) ON DELETE SET NULL,
    site_id INTEGER REFERENCES energy_sites(id) ON DELETE SET NULL,
    serial_port_config_id INTEGER REFERENCES serial_port_configs(id) ON DELETE SET NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(100) NOT NULL,
    meter_name VARCHAR(100),
    outstation INTEGER,
    type VARCHAR(20) NOT NULL,
    model VARCHAR(20) NOT NULL,
    survey_type VARCHAR(50)[],
    owner_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Asset Group Meters (many-to-many relationship)
CREATE TABLE IF NOT EXISTS asset_group_meters (
    asset_group_id INTEGER NOT NULL,
    meter_id INTEGER REFERENCES meters(id) ON DELETE CASCADE,
    PRIMARY KEY (asset_group_id, meter_id)
);

-- Indexes for reference tables
CREATE INDEX IF NOT EXISTS idx_meters_role ON meters(role_id);
CREATE INDEX IF NOT EXISTS idx_meters_source ON meters(source_id);
CREATE INDEX IF NOT EXISTS idx_meters_site ON meters(site_id);
CREATE INDEX IF NOT EXISTS idx_meters_owner ON meters(owner_id);
CREATE INDEX IF NOT EXISTS idx_meters_serial ON meters(serial_number);

-- ============================================
-- 1. METER PROFILE (Time-series data from EDMI meters)
-- ============================================
CREATE TABLE IF NOT EXISTS meter_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    meter_id VARCHAR(50) NOT NULL,
    
    -- Timestamp of the profile record (from LS03 "time_stamp")
    time_stamp TIMESTAMPTZ NOT NULL,
    
    -- LS03 "Record Status" (0.0 means valid interval record)
    record_status DECIMAL(15, 6),
    
    -- LS03 cumulative totals
    total_energy_tot_imp_wh DECIMAL(15, 6),  -- "Total Energy Tot IMP Wh @"
    total_energy_tot_exp_wh DECIMAL(15, 6),  -- "Total Energy Tot EXP Wh @"
    total_energy_tot_imp_va DECIMAL(15, 6),  -- "Total Energy Tot IMP va @"
    total_energy_tot_exp_va DECIMAL(15, 6),  -- "Total Energy Tot EXP va @"
    
    -- Meter role and source type (from configuration)
    meter_role VARCHAR(50),  -- SOURCE, SELF_USE, GRID_POINT, INTERCONNECT
    source_type VARCHAR(50),  -- BESS, RTS, etc.
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Unique constraint for meter + timestamp
    CONSTRAINT uq_meter_profile_meter_time 
        UNIQUE (controller_id, meter_id, time_stamp)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_meter_profile_controller_time 
    ON meter_profile(controller_id, time_stamp DESC);

CREATE INDEX IF NOT EXISTS idx_meter_profile_meter_time 
    ON meter_profile(meter_id, time_stamp DESC);

CREATE INDEX IF NOT EXISTS idx_meter_profile_role_time 
    ON meter_profile(meter_role, time_stamp DESC);

-- ============================================
-- 2. INTERVAL STATE (Time-series data)
-- ============================================
CREATE TABLE IF NOT EXISTS interval_state (
    ts TIMESTAMPTZ NOT NULL,
    controller_id VARCHAR(50) NOT NULL,
    
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    
    -- Availability flags
    self_available BOOLEAN NOT NULL DEFAULT FALSE,
    main_available BOOLEAN NOT NULL DEFAULT FALSE,
    backup_available BOOLEAN NOT NULL DEFAULT FALSE,
    bess_available BOOLEAN NOT NULL DEFAULT FALSE,
    rts_available BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Missing counts (0..4)
    bess_missing_count INTEGER NOT NULL DEFAULT 0,
    rts_missing_count INTEGER NOT NULL DEFAULT 0,
    main_missing_count INTEGER NOT NULL DEFAULT 0,
    backup_missing_count INTEGER NOT NULL DEFAULT 0,
    
    scenario_code VARCHAR(50) NOT NULL,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    PRIMARY KEY (ts, controller_id)
);

-- Index for controller + time range queries
CREATE INDEX IF NOT EXISTS idx_interval_state_controller_time 
    ON interval_state(controller_id, ts DESC);

CREATE INDEX IF NOT EXISTS idx_interval_state_scenario 
    ON interval_state(scenario_code, ts DESC);

-- ============================================
-- 3. CALCULATION PERIOD (Relational + Time-based)
-- ============================================
CREATE TABLE IF NOT EXISTS calculation_period (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    
    scenario_code VARCHAR(50) NOT NULL,
    formula_code VARCHAR(50) NOT NULL,
    
    interval_count INTEGER NOT NULL DEFAULT 0,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Ensure no overlapping periods for same controller
    CONSTRAINT uq_calculation_period_controller_time 
        UNIQUE (controller_id, period_start, period_end)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_calc_period_controller_time 
    ON calculation_period(controller_id, period_start DESC, period_end DESC);

CREATE INDEX IF NOT EXISTS idx_calc_period_scenario 
    ON calculation_period(scenario_code, period_start DESC);

CREATE INDEX IF NOT EXISTS idx_calc_period_formula 
    ON calculation_period(formula_code, period_start DESC);

CREATE INDEX IF NOT EXISTS idx_calc_period_month 
    ON calculation_period(year, month, controller_id);

-- ============================================
-- 4. MONTHLY ENERGY SUMMARY (Relational)
-- ============================================
CREATE TABLE IF NOT EXISTS monthly_energy_summary (
    id SERIAL PRIMARY KEY,
    controller_id VARCHAR(50) NOT NULL,
    
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    
    -- Totals for the month
    bess_to_lmv_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    rts_to_lmv_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    total_energy_to_lmv_kwh DECIMAL(15, 6) DEFAULT 0.0,
    k_factor DECIMAL(15, 6) DEFAULT 0.0,
    
    start_date_time TIMESTAMPTZ,
    end_date_time TIMESTAMPTZ,
    
    number_of_inqualified_intervals INTEGER DEFAULT 0,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Unique constraint for year/month per controller
    CONSTRAINT uq_monthly_summary_controller_month 
        UNIQUE (controller_id, year, month)
);

CREATE INDEX IF NOT EXISTS idx_monthly_summary_controller 
    ON monthly_energy_summary(controller_id, year DESC, month DESC);

-- ============================================
-- 5. MONTHLY CALCULATION BREAKDOWN (Relational)
-- ============================================
CREATE TABLE IF NOT EXISTS monthly_calculation_breakdown (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    
    -- Foreign key to monthly summary (optional, for relational queries)
    monthly_summary_id INTEGER REFERENCES monthly_energy_summary(id) ON DELETE CASCADE,
    
    -- Period info
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    interval_count INTEGER NOT NULL DEFAULT 0,
    
    -- Decision
    scenario_code VARCHAR(50) NOT NULL,
    formula_code VARCHAR(50) NOT NULL,
    
    -- Raw energy (this period)
    bess_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    rts_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    self_use_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    grid_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    interconnect_energy_kwh DECIMAL(15, 6) DEFAULT 0.0,
    
    -- K used
    k_factor DECIMAL(15, 6),
    
    -- Settled result
    rts_to_lmv_kwh DECIMAL(15, 6) DEFAULT 0.0,
    bess_to_lmv_kwh DECIMAL(15, 6) DEFAULT 0.0,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_breakdown_controller_period 
    ON monthly_calculation_breakdown(controller_id, period_start DESC, period_end DESC);

CREATE INDEX IF NOT EXISTS idx_breakdown_summary 
    ON monthly_calculation_breakdown(monthly_summary_id);

CREATE INDEX IF NOT EXISTS idx_breakdown_scenario 
    ON monthly_calculation_breakdown(scenario_code, period_start DESC);

CREATE INDEX IF NOT EXISTS idx_breakdown_formula 
    ON monthly_calculation_breakdown(formula_code, period_start DESC);

-- ============================================
-- 6. METER STATUS SUMMARY (Optional)
-- ============================================
CREATE TABLE IF NOT EXISTS meter_status_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    meter_id VARCHAR(50) NOT NULL,
    
    fault_start_ts TIMESTAMPTZ NOT NULL,
    fault_end_ts TIMESTAMPTZ,
    
    is_open BOOLEAN NOT NULL DEFAULT TRUE,
    source_period_id UUID NOT NULL,
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_meter_status_controller 
    ON meter_status_summary(controller_id, meter_id, fault_start_ts DESC);

CREATE INDEX IF NOT EXISTS idx_meter_status_open 
    ON meter_status_summary(controller_id, is_open, fault_start_ts DESC);

-- ============================================
-- 7. SCENARIO WINDOW (Optional)
-- ============================================
CREATE TABLE IF NOT EXISTS scenario_window (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    
    scenario_code VARCHAR(50) NOT NULL,
    window_start_ts TIMESTAMPTZ NOT NULL,
    window_end_ts TIMESTAMPTZ,
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scenario_window_controller 
    ON scenario_window(controller_id, scenario_code, window_start_ts DESC);

-- ============================================
-- 8. PROFILE READING DEMO (For demo/testing data)
-- ============================================
CREATE TABLE IF NOT EXISTS profile_reading_demo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    meter_id VARCHAR(50) NOT NULL,
    
    -- Timestamp of the profile record (from LS03 "time_stamp")
    time_stamp TIMESTAMPTZ NOT NULL,
    
    -- LS03 "Record Status" (0.0 means valid interval record)
    record_status DECIMAL(15, 6),
    
    -- LS03 cumulative totals
    total_energy_tot_imp_wh DECIMAL(15, 6),  -- "Total Energy Tot IMP Wh @"
    total_energy_tot_exp_wh DECIMAL(15, 6),  -- "Total Energy Tot EXP Wh @"
    total_energy_tot_imp_va DECIMAL(15, 6),  -- "Total Energy Tot IMP va @"
    total_energy_tot_exp_va DECIMAL(15, 6),  -- "Total Energy Tot EXP va @"
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Unique constraint for meter + timestamp
    CONSTRAINT uq_profile_reading_demo_meter_time 
        UNIQUE (controller_id, meter_id, time_stamp)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_profile_demo_controller_time 
    ON profile_reading_demo(controller_id, time_stamp DESC);

CREATE INDEX IF NOT EXISTS idx_profile_demo_meter_time 
    ON profile_reading_demo(meter_id, time_stamp DESC);

-- ============================================
-- 9. READING VALUE (Wide table for meter readings - every 30s)
-- ============================================
CREATE TABLE IF NOT EXISTS reading_value (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    meter_id VARCHAR(50) NOT NULL,
    
    -- Timestamp of the reading
    time_stamp_utc TIMESTAMPTZ NOT NULL,
    
    -- Multipliers / Divisors
    current_multiplier DECIMAL(10, 6),
    voltage_multiplier DECIMAL(10, 6),
    current_divisor DECIMAL(10, 6),
    voltage_divisor DECIMAL(10, 6),
    
    -- 3 Phase Voltages
    phase_a_voltage DECIMAL(10, 6),
    phase_b_voltage DECIMAL(10, 6),
    phase_c_voltage DECIMAL(10, 6),
    
    -- 3 Phase Currents
    phase_a_current DECIMAL(10, 6),
    phase_b_current DECIMAL(10, 6),
    phase_c_current DECIMAL(10, 6),
    
    -- 3 Phase Angles
    phase_a_angle DECIMAL(10, 6),
    phase_b_angle DECIMAL(10, 6),
    phase_c_angle DECIMAL(10, 6),
    vta_vtb_angle DECIMAL(10, 6),
    vta_vtc_angle DECIMAL(10, 6),
    
    -- 3 Phase Watts / Vars / VA
    phase_a_watts DECIMAL(10, 6),
    phase_b_watts DECIMAL(10, 6),
    phase_c_watts DECIMAL(10, 6),
    
    phase_a_vars DECIMAL(10, 6),
    phase_b_vars DECIMAL(10, 6),
    phase_c_vars DECIMAL(10, 6),
    
    phase_a_va DECIMAL(10, 6),
    phase_b_va DECIMAL(10, 6),
    phase_c_va DECIMAL(10, 6),
    
    -- Power / Frequency
    power_factor DECIMAL(10, 6),
    frequency DECIMAL(10, 6),
    
    -- Energy Import (kWh)
    rate_1_import_kwh DECIMAL(15, 6),
    rate_2_import_kwh DECIMAL(15, 6),
    rate_3_import_kwh DECIMAL(15, 6),
    total_import_kwh DECIMAL(15, 6),
    total_import_kvar DECIMAL(15, 6),
    
    -- Energy Export (kWh)
    rate_1_export_kwh DECIMAL(15, 6),
    rate_2_export_kwh DECIMAL(15, 6),
    rate_3_export_kwh DECIMAL(15, 6),
    total_export_kwh DECIMAL(15, 6),
    total_export_kvar DECIMAL(15, 6),
    
    -- THD
    thd_voltage_a DECIMAL(10, 6),
    thd_voltage_b DECIMAL(10, 6),
    thd_voltage_c DECIMAL(10, 6),
    thd_current_a DECIMAL(10, 6),
    thd_current_b DECIMAL(10, 6),
    thd_current_c DECIMAL(10, 6),
    
    -- Totals
    p_total DECIMAL(10, 6),
    q_total DECIMAL(10, 6),
    s_total DECIMAL(10, 6),
    
    -- Ratios
    ct_ratio_primary DECIMAL(10, 6),
    ct_ratio_secondary DECIMAL(10, 6),
    vt_ratio_primary DECIMAL(10, 6),
    vt_ratio_secondary DECIMAL(10, 6),
    
    -- Diagnostics / Demand
    error_code VARCHAR(50),
    max_demand_kwh_import DECIMAL(15, 6),
    max_demand_kwh_export DECIMAL(15, 6),
    
    -- Meter Information
    meter_serial_number VARCHAR(32),
    meter_current_date DATE,
    meter_current_time TIME,
    meter_date_time TIMESTAMPTZ,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Unique constraint for meter + timestamp
    CONSTRAINT uq_reading_value_meter_time 
        UNIQUE (controller_id, meter_id, time_stamp_utc)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_reading_controller_time 
    ON reading_value(controller_id, time_stamp_utc DESC);

CREATE INDEX IF NOT EXISTS idx_reading_meter_time 
    ON reading_value(meter_id, time_stamp_utc DESC);

-- ============================================
-- 10. PROFILE READ GAP (For tracking missed reads)
-- ============================================
CREATE TABLE IF NOT EXISTS profile_read_gap (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    meter_id VARCHAR(50) NOT NULL,
    serial_number VARCHAR(50),
    
    from_dt TIMESTAMPTZ NOT NULL,
    to_dt TIMESTAMPTZ NOT NULL,
    
    -- pending → done | failed
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    
    retry_count INTEGER NOT NULL DEFAULT 0,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Unique constraint for meter + time window
    CONSTRAINT uq_profile_gap_meter_window 
        UNIQUE (controller_id, meter_id, from_dt, to_dt)
);

CREATE INDEX IF NOT EXISTS idx_profile_gap_meter_status 
    ON profile_read_gap(controller_id, meter_id, status);

CREATE INDEX IF NOT EXISTS idx_profile_gap_serial_status 
    ON profile_read_gap(serial_number, status);

CREATE INDEX IF NOT EXISTS idx_profile_gap_pending 
    ON profile_read_gap(status, created_at) 
    WHERE status = 'pending';

-- ============================================
-- FUNCTIONS & TRIGGERS
-- ============================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Triggers for updated_at
DROP TRIGGER IF EXISTS update_calc_period_updated_at ON calculation_period;
CREATE TRIGGER update_calc_period_updated_at
    BEFORE UPDATE ON calculation_period
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_monthly_summary_updated_at ON monthly_energy_summary;
CREATE TRIGGER update_monthly_summary_updated_at
    BEFORE UPDATE ON monthly_energy_summary
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_breakdown_updated_at ON monthly_calculation_breakdown;
CREATE TRIGGER update_breakdown_updated_at
    BEFORE UPDATE ON monthly_calculation_breakdown
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_profile_gap_updated_at ON profile_read_gap;
CREATE TRIGGER update_profile_gap_updated_at
    BEFORE UPDATE ON profile_read_gap
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- EXAMPLE QUERIES
-- ============================================

-- Get all meter profiles for a specific time range
-- SELECT * FROM meter_profile 
-- WHERE controller_id = 'ctrlEnergyCalc0' 
-- AND time_stamp >= '2025-01-01' AND time_stamp < '2025-02-01'
-- ORDER BY time_stamp;

-- Get all reading values for a specific meter (30s interval data)
-- SELECT * FROM reading_value 
-- WHERE controller_id = 'ctrlEnergyCalc0' 
-- AND meter_id = 'METER_01'
-- AND time_stamp_utc >= '2025-01-01' AND time_stamp_utc < '2025-02-01'
-- ORDER BY time_stamp_utc;

-- Get all breakdowns for a specific month
-- SELECT * FROM monthly_calculation_breakdown 
-- WHERE controller_id = 'ctrlEnergyCalc0' 
-- AND period_start >= '2025-01-01' AND period_start < '2025-02-01'
-- ORDER BY period_start;

-- Get monthly summary with breakdowns
-- SELECT 
--     s.year, s.month, s.total_energy_to_lmv_kwh,
--     COUNT(b.id) as num_periods,
--     SUM(b.bess_energy_kwh) as bess_total
-- FROM monthly_energy_summary s
-- LEFT JOIN monthly_calculation_breakdown b 
--     ON b.monthly_summary_id = s.id
-- WHERE s.controller_id = 'ctrlEnergyCalc0'
-- GROUP BY s.id, s.year, s.month;

-- Get periods by scenario
-- SELECT 
--     scenario_code, formula_code,
--     COUNT(*) as count,
--     SUM(bess_to_lmv_kwh) as total_bess
-- FROM monthly_calculation_breakdown
-- WHERE controller_id = 'ctrlEnergyCalc0'
-- AND period_start >= '2025-01-01'
-- GROUP BY scenario_code, formula_code;

-- Get pending profile read gaps (for retry logic)
-- SELECT * FROM profile_read_gap 
-- WHERE controller_id = 'ctrlEnergyCalc0' 
-- AND status = 'pending'
-- ORDER BY created_at;
