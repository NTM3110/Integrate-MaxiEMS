-- TimescaleDB Schema for Energy Calculation Reports
-- Based on existing PostgreSQL model from energy_app

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ============================================
-- 0. PROFILE CONFIGURATION (Application settings)
-- ============================================
CREATE TABLE profile_config (
    id SERIAL PRIMARY KEY,
    
    -- Survey type (e.g., LS02, LS03)
    survey VARCHAR(20) NOT NULL DEFAULT 'LS02',
    
    -- Timing configuration
    interval_minutes INTEGER NOT NULL DEFAULT 30,
    first_read_delay_minutes INTEGER NOT NULL DEFAULT 5,
    retry_minutes INTEGER NOT NULL DEFAULT 5,
    finalize_after_minutes INTEGER NOT NULL DEFAULT 30,
    max_records INTEGER NOT NULL DEFAULT 5,
    
    -- Field mappings (comma-separated aliases)
    field_timestamp TEXT NOT NULL DEFAULT 'DateTime,time_stamp,timestamp',
    field_record_status TEXT NOT NULL DEFAULT 'Record Status,record_status,EFA Status',
    field_total_exp_wh TEXT NOT NULL DEFAULT 'Total Energy Channel 1 @R,Total Energy Channel 1 @RUnified,Total Energy Tot EXP Wh @,To EXP Wh,Tot EXP Wh',
    field_total_imp_wh TEXT NOT NULL DEFAULT 'Total Energy Channel 2 @R,Total Energy Channel 2 @RUnified,Total Energy Tot IMP Wh @,To IMP Wh,Tot IMP Wh',
    field_total_exp_va TEXT NOT NULL DEFAULT 'Total Energy Channel 3 @R,Total Energy Channel 3 @RUnified,Total Energy Tot EXP va @,To EXP va,Tot EXP va',
    field_total_imp_va TEXT NOT NULL DEFAULT 'Total Energy Channel 4 @R,Total Energy Channel 4 @RUnified,Total Energy Tot IMP va @,To IMP va,Tot IMP va',
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default configuration
INSERT INTO profile_config (survey, interval_minutes, first_read_delay_minutes, retry_minutes, 
    finalize_after_minutes, max_records, field_timestamp, field_record_status, 
    field_total_exp_wh, field_total_imp_wh, field_total_exp_va, field_total_imp_va)
VALUES ('LS02', 30, 5, 5, 30, 5, 
    'DateTime,time_stamp,timestamp',
    'Record Status,record_status,EFA Status',
    'Total Energy Channel 1 @R,Total Energy Channel 1 @RUnified,Total Energy Tot EXP Wh @,To EXP Wh,Tot EXP Wh',
    'Total Energy Channel 2 @R,Total Energy Channel 2 @RUnified,Total Energy Tot IMP Wh @,To IMP Wh,Tot IMP Wh',
    'Total Energy Channel 3 @R,Total Energy Channel 3 @RUnified,Total Energy Tot EXP va @,To EXP va,Tot EXP va',
    'Total Energy Channel 4 @R,Total Energy Channel 4 @RUnified,Total Energy Tot IMP va @,To IMP va,Tot IMP va')
ON CONFLICT DO NOTHING;

-- ============================================
-- 1. METER PROFILE (Time-series data from EDMI meters)
-- ============================================
CREATE TABLE meter_profile (
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

-- Convert to hypertable for time-series optimization
SELECT create_hypertable('meter_profile', 'time_stamp', 
    chunk_time_interval => INTERVAL '1 month',
    if_not_exists => TRUE
);

-- Indexes for common queries
CREATE INDEX idx_meter_profile_controller_time 
    ON meter_profile(controller_id, time_stamp DESC);

CREATE INDEX idx_meter_profile_meter_time 
    ON meter_profile(meter_id, time_stamp DESC);

CREATE INDEX idx_meter_profile_role_time 
    ON meter_profile(meter_role, time_stamp DESC);

-- ============================================
-- 2. INTERVAL STATE (Time-series data)
-- ============================================
CREATE TABLE interval_state (
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

-- Convert to hypertable for time-series optimization
SELECT create_hypertable('interval_state', 'ts', 
    chunk_time_interval => INTERVAL '1 month',
    if_not_exists => TRUE
);

-- Index for controller + time range queries
CREATE INDEX idx_interval_state_controller_time 
    ON interval_state(controller_id, ts DESC);

CREATE INDEX idx_interval_state_scenario 
    ON interval_state(scenario_code, ts DESC);

-- ============================================
-- 3. CALCULATION PERIOD (Relational + Time-based)
-- ============================================
CREATE TABLE calculation_period (
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
CREATE INDEX idx_calc_period_controller_time 
    ON calculation_period(controller_id, period_start DESC, period_end DESC);

CREATE INDEX idx_calc_period_scenario 
    ON calculation_period(scenario_code, period_start DESC);

CREATE INDEX idx_calc_period_formula 
    ON calculation_period(formula_code, period_start DESC);

CREATE INDEX idx_calc_period_month 
    ON calculation_period(year, month, controller_id);

-- ============================================
-- 4. MONTHLY ENERGY SUMMARY (Relational)
-- ============================================
CREATE TABLE monthly_energy_summary (
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

CREATE INDEX idx_monthly_summary_controller 
    ON monthly_energy_summary(controller_id, year DESC, month DESC);

-- ============================================
-- 5. MONTHLY CALCULATION BREAKDOWN (Relational)
-- ============================================
CREATE TABLE monthly_calculation_breakdown (
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
CREATE INDEX idx_breakdown_controller_period 
    ON monthly_calculation_breakdown(controller_id, period_start DESC, period_end DESC);

CREATE INDEX idx_breakdown_summary 
    ON monthly_calculation_breakdown(monthly_summary_id);

CREATE INDEX idx_breakdown_scenario 
    ON monthly_calculation_breakdown(scenario_code, period_start DESC);

CREATE INDEX idx_breakdown_formula 
    ON monthly_calculation_breakdown(formula_code, period_start DESC);

-- ============================================
-- 6. METER STATUS SUMMARY (Optional)
-- ============================================
CREATE TABLE meter_status_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    meter_id VARCHAR(50) NOT NULL,
    
    fault_start_ts TIMESTAMPTZ NOT NULL,
    fault_end_ts TIMESTAMPTZ,
    
    is_open BOOLEAN NOT NULL DEFAULT TRUE,
    source_period_id UUID NOT NULL,
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_meter_status_controller 
    ON meter_status_summary(controller_id, meter_id, fault_start_ts DESC);

CREATE INDEX idx_meter_status_open 
    ON meter_status_summary(controller_id, is_open, fault_start_ts DESC);

-- ============================================
-- 7. SCENARIO WINDOW (Optional)
-- ============================================
CREATE TABLE scenario_window (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id VARCHAR(50) NOT NULL,
    
    scenario_code VARCHAR(50) NOT NULL,
    window_start_ts TIMESTAMPTZ NOT NULL,
    window_end_ts TIMESTAMPTZ,
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_scenario_window_controller 
    ON scenario_window(controller_id, scenario_code, window_start_ts DESC);

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
CREATE TRIGGER update_calc_period_updated_at
    BEFORE UPDATE ON calculation_period
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_monthly_summary_updated_at
    BEFORE UPDATE ON monthly_energy_summary
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_breakdown_updated_at
    BEFORE UPDATE ON monthly_calculation_breakdown
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- CONTINUOUS AGGREGATES (Optional - for performance)
-- ============================================

-- Monthly summary from breakdowns (auto-maintained)
CREATE MATERIALIZED VIEW monthly_summary_continuous
WITH (timescaledb.continuous) AS
SELECT
    controller_id,
    time_bucket('1 month', period_start) AS month_bucket,
    scenario_code,
    formula_code,
    COUNT(*) as period_count,
    SUM(interval_count) as total_intervals,
    SUM(bess_energy_kwh) as total_bess_energy,
    SUM(rts_energy_kwh) as total_rts_energy,
    SUM(self_use_energy_kwh) as total_self_use_energy,
    SUM(grid_energy_kwh) as total_grid_energy,
    SUM(interconnect_energy_kwh) as total_interconnect_energy,
    AVG(k_factor) as avg_k_factor,
    SUM(rts_to_lmv_kwh) as total_rts_to_lmv,
    SUM(bess_to_lmv_kwh) as total_bess_to_lmv
FROM monthly_calculation_breakdown
GROUP BY controller_id, time_bucket('1 month', period_start), scenario_code, formula_code
WITH NO DATA;

-- Refresh policy: every hour
SELECT add_continuous_aggregate_policy('monthly_summary_continuous',
    start_offset => INTERVAL '3 months',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
);

-- ============================================
-- 8. PROFILE READING DEMO (For demo/testing data)
-- ============================================
CREATE TABLE profile_reading_demo (
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

-- Convert to hypertable for time-series optimization
SELECT create_hypertable('profile_reading_demo', 'time_stamp', 
    chunk_time_interval => INTERVAL '1 month',
    if_not_exists => TRUE
);

-- Indexes for common queries
CREATE INDEX idx_profile_demo_controller_time 
    ON profile_reading_demo(controller_id, time_stamp DESC);

CREATE INDEX idx_profile_demo_meter_time 
    ON profile_reading_demo(meter_id, time_stamp DESC);

-- ============================================
-- 9. READING VALUE (Wide table for meter readings - every 30s)
-- ============================================
CREATE TABLE reading_value (
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
    current_date DATE,
    current_time TIME,
    meter_date_time TIMESTAMPTZ,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Unique constraint for meter + timestamp
    CONSTRAINT uq_reading_value_meter_time 
        UNIQUE (controller_id, meter_id, time_stamp_utc)
);

-- Convert to hypertable for time-series optimization
SELECT create_hypertable('reading_value', 'time_stamp_utc', 
    chunk_time_interval => INTERVAL '1 week',
    if_not_exists => TRUE
);

-- Indexes for common queries
CREATE INDEX idx_reading_controller_time 
    ON reading_value(controller_id, time_stamp_utc DESC);

CREATE INDEX idx_reading_meter_time 
    ON reading_value(meter_id, time_stamp_utc DESC);

-- ============================================
-- 10. PROFILE READ GAP (For tracking missed reads)
-- ============================================
CREATE TABLE profile_read_gap (
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

CREATE INDEX idx_profile_gap_meter_status 
    ON profile_read_gap(controller_id, meter_id, status);

CREATE INDEX idx_profile_gap_serial_status 
    ON profile_read_gap(serial_number, status);

CREATE INDEX idx_profile_gap_pending 
    ON profile_read_gap(status, created_at) 
    WHERE status = 'pending';

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
