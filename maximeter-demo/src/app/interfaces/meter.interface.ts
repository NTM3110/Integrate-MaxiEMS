/**
 * Meter (Công tơ) Interface
 * Represents a meter in the system
 */
export interface Meter {
    id: number;                     // ID số thứ tự (1, 2, 3...)
    serialNumber: string;           // S/N công tơ
    meterPointName: string;         // Tên Điểm đo (Công tơ Chính, Công tơ DP)
    meterPointCode: string;         // Mã điểm đo
    meterType: string;              // Loại công tơ
    meterKind?: string;             // Meter type from API
    outstationNumber: string;       // Outstation Number
    multiplier: number;             // Hệ số nhân
    surveyTypes?: string[];         // Survey types supported
    roleId?: number | null;         // Energy role ID
    sourceId?: number | null;       // Energy source ID
    connectionStatus: 'connected' | 'failed' | 'not_connected';  // Trạng thái kết nối
    readingStatus: 'reading' | 'completed';  // Tình trạng đọc (Đang đọc, Kết thúc)
    lastUpdated?: Date;
}

/**
 * Meter Operating Parameters (Thông số vận hành công tơ)
 */
export interface MeterOperatingParams {
    timestamp: Date;               // Thời điểm
    phase: 'A' | 'B' | 'C' | 'Tổng';  // Pha
    voltage: number;               // Điện áp (V)
    current: number;               // Dòng điện (A)
    activePower: number;           // P (kW)
    reactivePower: number;         // Q (kVAR)
    powerFactor: number;           // Hệ số công suất
    frequency?: number;            // Tần số (Hz)
}

/**
 * Meter Reading at specific time (Chỉ số từng thời điểm)
 */
export interface MeterPeriodicReading {
    id: number;
    timestamp: Date;               // Thời điểm
    phase: number;                 // Hệ số
    activeImport: number;          // Chỉ số P Giao (kWh)
    activeExport?: number;         // P Nhập (kWh)
    reactiveImport?: number;       // Q Giao (kVARh)
    reactiveExport?: number;       // Q Nhập (kVARh)
    pMax: number;                  // PMax (kW)
    pMaxTime?: Date;               // Thời gian PMax
}

/**
 * Finalized Meter Reading (Chỉ số chốt)
 */
export interface MeterFinalizedReading {
    id: number;
    readingDateTime: string;       // HT (Thời điểm đọc)
    phase: number;                 // Hệ số
    bieuGia: string;               // Biểu giá
    activeImportIndex: number;     // Chỉ số P Giao (kWh)
    reactiveImportIndex?: number;  // Chỉ số Q Giao (kVARh)
    reactiveExportIndex?: number;  // Chỉ số Q Nhập (kVARh)
    pMax: number;                  // PMax (kW)
    pMaxTime?: Date;               // Thời gian PMax
}

/**
 * Meter statistics for dashboard cards
 */
export interface MeterStats {
    totalMeters: number;
    onlineMeters: number;
    offlineMeters: number;
    readingMeters: number;
}

/**
 * Meter detail with all related data
 */
export interface MeterDetail extends Meter {
    meterInfo: {
        imei: string;
        location: string;
        installDate?: Date;
        lastReadDate?: Date;
        totalRecords?: number;
    };
    operatingParams: MeterOperatingParams[];
    periodicReadings: MeterPeriodicReading[];
    finalizedReadings: MeterFinalizedReading[];
}
