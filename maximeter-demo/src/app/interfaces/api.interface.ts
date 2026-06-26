/**
 * API Interfaces - Backend Response/Request Models
 */

// ============================================
// Meter Info API
// ============================================

/**
 * Meter info from GET /api/get_all_meters_info
 */
export interface ApiMeterInfo {
    meter_id: number;
    serial_number: number | string | null;
    password: string | null;
    username: string | null;
    owner_id?: number;
    meter_name?: string | null;
    outstation?: number | null;
    type?: string | null;
    model?: string | null;
    survey_type?: string[] | string | null;
    serial_port_config_id?: number | null;
    role?: number | null;
    source_id?: number | null;
}

// ============================================
// Reading Loop API
// ============================================

/**
 * Request for POST /api/read_and_save_meters_loop
 */
export interface StartLoopRequest {
    meters_id_list: number[];
}

/**
 * Response from POST /api/read_and_save_meters_loop
 */
export interface StartLoopResponse {
    status: 'started' | 'already_running';
    task_id: string;
    available_ids: number[];
    functional_ids: number[];
}

export interface MeterStatusEntry {
    meter_id: number;
    status: string;
    updated_at: string;
}

export interface StartLoopStreamStatusData extends StartLoopResponse {
    meter_status: MeterStatusEntry[];
}

export interface LoopMeterStatusData {
    task_id: string;
    meter_status: MeterStatusEntry[];
    slot_ts?: string;
}

export interface MeterLoopStreamEvent<T = StartLoopStreamStatusData | LoopMeterStatusData> {
    event: 'status' | 'meter_status';
    data: T;
    id?: string;
}

/**
 * Response from GET /api/read_and_save_meters_loop_stop
 */
export interface StopLoopResponse {
    status: 'stopping' | 'not_running';
    task_id: string | null;
}

/**
 * Response from GET /api/meter_loop_status (polling)
 */
export interface MeterLoopStatusResponse {
    task_id: string | null;
    loop_state: string;
    is_running: boolean;
    functional_ids: number[];
    meter_status: MeterStatusEntry[];
    slot_ts: string | null;
}

export interface EnergyRole {
    id: number;
    name: string;
    description?: string;
}

export interface EnergySource {
    id: number;
    name: string;
    description?: string;
}

export interface EnergyItemListResponse<T> {
    items: T[];
}

export interface EnergyRecalculateResponse {
    status: 'ok' | 'skipped';
    processed: boolean;
    timestamp_count: number;
    affected_months: { year: number; month: number }[];
    reason?: string | null;
}


// ============================================
// Meter CRUD API
// ============================================

/**
 * Request for POST /api/add_meter
 */
export interface AddMeterRequest {
    serial_number: number | string | null;
    username: string | null;
    password: string | null;
    owner_id: number;
    meter_name?: string | null;
    outstation?: number | null;
    type: string;
    model: string;
    survey_type?: string[] | string | null;
    serial_port_config_id?: number | null;
    role?: number | null;
    source_id?: number | null;
}

/**
 * Response from POST /api/add_meter
 */
export interface AddMeterResponse {
    serial_number: number | string | null;
    status: string;
}

/**
 * Request for PUT /api/update_meter
 */
export interface UpdateMeterRequest {
    serial_number: number | string | null;
    username: string | null;
    password: string | null;
    owner_id: number;
    meter_name?: string | null;
    outstation?: number | null;
    type: string;
    model: string;
    survey_type?: string[] | string | null;
    serial_port_config_id?: number | null;
    role?: number | null;
    source_id?: number | null;
}

/**
 * Response from PUT /api/update_meter
 */
export interface UpdateMeterResponse {
    status: string;
    serial_number: number | null;
}

/**
 * Response from DELETE /api/delete_meter/{id}
 */
export interface DeleteMeterResponse {
    status: string;
    meter_id?: number;
    detail?: string;
}

// ============================================
// Serial Port Configuration API
// ============================================

export type SerialPortParity = 'none' | 'even' | 'odd' | 'mark' | 'space';

export interface ApiSerialPortConfig {
    id: number;
    display_name: string;
    port: string;
    baud_rate: number;
    data_bits: number;
    stop_bits: number;
    parity: SerialPortParity;
}

export interface SerialPortConfigRequest {
    display_name: string;
    port: string;
    baud_rate: number;
    data_bits: number;
    stop_bits: number;
    parity: SerialPortParity;
}

export interface DeleteSerialPortResponse {
    status: string;
    id?: number;
}

// ============================================
// Query Data API
// ============================================

/**
 * Time range for query
 */
export interface TimeRange {
    start_utc: string;  // ISO 8601 format
    end_utc: string;    // ISO 8601 format
}

/**
 * Request for POST /api/query_data_by_time_range
 */
export interface QueryDataByTimeRangeRequest {
    meter_id: number;
    columns: string[];
    time_range: TimeRange;
    limit?: number;
    order?: 'asc' | 'desc';
    interval_seconds?: number;
}

/**
 * Single data point from query
 */
export interface MeterDataPoint {
    time_stamp: string;
    [key: string]: any;  // Dynamic columns
}

/**
 * Response from POST /api/query_data_by_time_range
 */
export interface QueryDataByTimeRangeResponse {
    meter_id: number;
    columns: string[];
    interval_seconds: number;
    time_range: TimeRange;
    count: number;
    data: MeterDataPoint[];
}

/**
 * Request for POST /api/query_reading_latest
 */
export interface QueryLatestRequest {
    meter_id: number;
    columns: string[];
    count?: number;
    order?: 'asc' | 'desc';
    interval_seconds?: number;
}

/**
 * Response from POST /api/query_reading_latest
 */
export interface QueryLatestResponse {
    meter_id: number;
    columns: string[];
    order: string;
    interval_seconds: number | null;
    count: number;
    data: MeterDataPoint[];
}

// ============================================
// Profile Survey and Channel Mapping API
// ============================================

export interface ProfileConfig {
    survey: string;
    interval_minutes: number;
    first_read_delay_minutes: number;
    retry_minutes: number;
    finalize_after_minutes: number;
    max_records: number;
    field_timestamp: string;
    field_record_status: string;
    field_total_exp_wh: string;
    field_total_imp_wh: string;
    field_total_exp_va: string;
    field_total_imp_va: string;
    config_file?: string | null;
    saved?: boolean;
}

export type ProfileConfigRequest = Omit<ProfileConfig, 'config_file' | 'saved'>;
// ============================================
// Load Profile API
// ============================================

/**
 * Request for POST /api/read_profile
 */
export interface ReadProfileRequest {
    meter_id: number;
    survey: string;
    from_datetime: string;
    to_datetime: string;
    max_records?: number;
}

/**
 * Response from POST /api/read_profile
 */
export interface ReadProfileResponse {
    status: string;
    meter_id: number;
    survey: string;
    field: string[];
    interval_seconds: number;
    count: number;
    data: MeterDataPoint[];
}

export interface ReadProfilesForEnergyRequest {
    survey: string;
    from_datetime: string;
    to_datetime: string;
    max_records?: number | null;
}

export interface ReadProfilesForEnergyMeterResult {
    meter_id: number;
    serial_number: string;
    meter_name?: string | null;
    status: string;
    read_count: number;
    saved_count: number;
    skipped_count: number;
    timestamps: string[];
    error?: string | null;
}

export interface ReadProfilesForEnergyResponse {
    status: 'ok' | 'partial';
    survey: string;
    from_datetime: string;
    to_datetime: string;
    max_records: number;
    total_meters: number;
    succeeded_meters: number;
    failed_meters: number;
    read_rows: number;
    saved_rows: number;
    results: ReadProfilesForEnergyMeterResult[];
}

// ============================================
// Available meter data columns
// ============================================

export interface ColumnDefinition {
    column_name: string;
    description: string;
    type: string;
    unit: string;
}

export const COLUMN_DEFINITIONS: ColumnDefinition[] = [
    { column_name: 'current_multiplier', description: 'Current multiplier', type: 'float', unit: '' },
    { column_name: 'voltage_multiplier', description: 'Voltage multiplier', type: 'float', unit: '' },
    { column_name: 'current_divisor', description: 'Current divisor', type: 'float', unit: '' },
    { column_name: 'voltage_divisor', description: 'Voltage divisor', type: 'float', unit: '' },
    { column_name: 'phase_a_voltage', description: 'Phase A voltage', type: 'float', unit: 'V' },
    { column_name: 'phase_b_voltage', description: 'Phase B voltage', type: 'float', unit: 'V' },
    { column_name: 'phase_c_voltage', description: 'Phase C voltage', type: 'float', unit: 'V' },
    { column_name: 'phase_a_current', description: 'Phase A current', type: 'float', unit: 'A' },
    { column_name: 'phase_b_current', description: 'Phase B current', type: 'float', unit: 'A' },
    { column_name: 'phase_c_current', description: 'Phase C current', type: 'float', unit: 'A' },
    { column_name: 'phase_a_angle', description: 'Phase A angle', type: 'float', unit: 'deg' },
    { column_name: 'phase_b_angle', description: 'Phase B angle', type: 'float', unit: 'deg' },
    { column_name: 'phase_c_angle', description: 'Phase C angle', type: 'float', unit: 'deg' },
    { column_name: 'vta_vtb_angle', description: 'Voltage TAâ€“TB angle', type: 'float', unit: 'deg' },
    { column_name: 'vta_vtc_angle', description: 'Voltage TAâ€“TC angle', type: 'float', unit: 'deg' },
    { column_name: 'phase_a_watts', description: 'Phase A active power', type: 'float', unit: 'W' },
    { column_name: 'phase_b_watts', description: 'Phase B active power', type: 'float', unit: 'W' },
    { column_name: 'phase_c_watts', description: 'Phase C active power', type: 'float', unit: 'W' },
    { column_name: 'phase_a_vars', description: 'Phase A reactive power', type: 'float', unit: 'var' },
    { column_name: 'phase_b_vars', description: 'Phase B reactive power', type: 'float', unit: 'var' },
    { column_name: 'phase_c_vars', description: 'Phase C reactive power', type: 'float', unit: 'var' },
    { column_name: 'phase_a_va', description: 'Phase A apparent power', type: 'float', unit: 'VA' },
    { column_name: 'phase_b_va', description: 'Phase B apparent power', type: 'float', unit: 'VA' },
    { column_name: 'phase_c_va', description: 'Phase C apparent power', type: 'float', unit: 'VA' },
    { column_name: 'power_factor', description: 'Power factor', type: 'float', unit: '' },
    { column_name: 'frequency', description: 'Line frequency', type: 'float', unit: 'Hz' },
    { column_name: 'rate_1_import_kwh', description: 'Rate 1 import energy', type: 'double', unit: 'kWh' },
    { column_name: 'rate_2_import_kwh', description: 'Rate 2 import energy', type: 'double', unit: 'kWh' },
    { column_name: 'rate_3_import_kwh', description: 'Rate 3 import energy', type: 'double', unit: 'kWh' },
    { column_name: 'total_import_kwh', description: 'Total import energy', type: 'double', unit: 'kWh' },
    { column_name: 'total_import_kvar', description: 'Total import reactive energy', type: 'double', unit: 'kvarh' },
    { column_name: 'rate_1_export_kwh', description: 'Rate 1 export energy', type: 'double', unit: 'kWh' },
    { column_name: 'rate_2_export_kwh', description: 'Rate 2 export energy', type: 'double', unit: 'kWh' },
    { column_name: 'rate_3_export_kwh', description: 'Rate 3 export energy', type: 'double', unit: 'kWh' },
    { column_name: 'total_export_kwh', description: 'Total export energy', type: 'double', unit: 'kWh' },
    { column_name: 'total_export_kvar', description: 'Total export reactive energy', type: 'double', unit: 'kvarh' },
    { column_name: 'p_total', description: 'Total active power', type: 'float', unit: 'W' },
    { column_name: 'q_total', description: 'Total reactive power', type: 'float', unit: 'var' },
    { column_name: 's_total', description: 'Total apparent power', type: 'float', unit: 'VA' },
    { column_name: 'thd_voltage_a', description: 'Voltage THD phase A', type: 'float', unit: '%' },
    { column_name: 'thd_voltage_b', description: 'Voltage THD phase B', type: 'float', unit: '%' },
    { column_name: 'thd_voltage_c', description: 'Voltage THD phase C', type: 'float', unit: '%' },
    { column_name: 'thd_current_a', description: 'Current THD phase A', type: 'float', unit: '%' },
    { column_name: 'thd_current_b', description: 'Current THD phase B', type: 'float', unit: '%' },
    { column_name: 'thd_current_c', description: 'Current THD phase C', type: 'float', unit: '%' },
    { column_name: 'error_code', description: 'Error code', type: 'string', unit: '' },
    { column_name: 'max_demand_kwh_import', description: 'Maximum demand import', type: 'double', unit: 'kWh' },
    { column_name: 'max_demand_kwh_export', description: 'Maximum demand export', type: 'double', unit: 'kWh' },
];

export const METER_DATA_COLUMNS = COLUMN_DEFINITIONS.map(c => c.column_name);

export type MeterDataColumn = typeof METER_DATA_COLUMNS[number];

// ============================================
// Energy Report API
// ============================================

/**
 * Request parameters for GET /api/energy/monthly-summary
 */
export interface MonthlySummaryRequest {
    year: number;
    month: number;
}

/**
 * Response from GET /api/energy/monthly-summary
 */
export interface MonthlySummary {
    id?: string;
    year: number;
    month: number;
    bess_to_lmv_energy_kwh: number;
    rfs_to_lmv_energy_kwh: number;
    total_energy_to_lmv_kwh: number;
    k_factor: number;
    start_date_time: string;
    end_date_time: string;
    number_of_inqualified_intervals?: number;
    [key: string]: any;  // Allow for additional fields
}

export interface MonthlySummaryResponse {
    items: MonthlySummary[];
    total: number;
}

/**
 * Request parameters for GET /api/energy/monthly-breakdown
 */
export interface MonthlyBreakdownRequest {
    year: number;
    month: number;
}

/**
 * Single period in monthly breakdown
 */
export interface PeriodBreakdown {
    id: string;
    period_start: string;
    period_end: string;
    interval_count: number;
    monthly_summary_id: number;
    formula_code: string;
    its_energy_kwh: number;
    grid_energy_kwh: number;
    k_factor: number;
    bess_energy_kwh: number;
    bess_to_lmv_kwh: number;
    its_to_lmv_kwh: number;
    self_use_energy_kwh: number;
    interconnect_energy_kwh: number;
    rts_to_lmv_kwh: number;
    scenario_code: string;
    [key: string]: any;  // Allow for additional fields
}

/**
 * Response from GET /api/energy/monthly-breakdown
 */
export interface MonthlyBreakdownResponse {
    items: PeriodBreakdown[];
    total: number;
}

/**
 * Meter status entry for a specific fault period
 */
export interface MeterFaultStatusEntry {
    meter_id: number;
    meter_name: string;
    serial_number: string;
    status: string;
    fault_code: string;
    timestamp?: string;
    [key: string]: any;
}


/**
 * Response for meter status summary by period
 */
/**
 * Meter Fault model
 */
export interface MeterFault {
    meter_id: number;
    meter_serial: string;
    meter_name: string;
    scenario_code?: string;
    fault_start_ts: string;
    fault_end_ts: string;
}

/**
 * Response from GET /api/faults/by-window
 * Also used as items in GET /api/faults/by-month
 */
export interface FaultWindowResponse {
    id: string;
    scenario_code: string;
    window_start_ts: string;
    window_end_ts: string;
    faults?: MeterFault[];
}

/**
 * Parameters for GET /api/faults/by-window
 */
export interface FaultsByWindowRequest {
    window_start_ts: string;
    window_end_ts: string;
}

/**
 * Parameters for GET /api/faults/by-month
 */
export interface FaultsByMonthRequest {
    year: number;
    month: number;
}

