import { Injectable, inject } from '@angular/core';
import { Observable, of, map, catchError, combineLatest } from 'rxjs';
import {
    Meter,
    MeterStats,
    MeterDetail,
    MeterOperatingParams,
    MeterPeriodicReading,
    MeterFinalizedReading,
} from '../interfaces/meter.interface';
import { MeterApiService } from './meter-api.service';
import { ApiMeterInfo, MeterDataPoint } from '../interfaces/api.interface';
import {
    MeterLoopStatusService,
    MeterLoopStatus,
    mapRawStatusToConnection,
} from './meter-loop-status.service';

@Injectable({
    providedIn: 'root',
})
export class MeterService {
    private meterApi = inject(MeterApiService);
    private loopStatusService = inject(MeterLoopStatusService);

    /**
     * Get meter statistics for dashboard cards
     */
    getMeterStats(): Observable<MeterStats> {
        return combineLatest([
            this.meterApi.getAllMetersInfo(),
            this.loopStatusService.status$
        ]).pipe(
            map(([meters, status]) => {
                const functionalCount = status.isRunning
                    ? this.countConnectedMeters(status)
                    : 0;
                return {
                    totalMeters: meters.length,
                    onlineMeters: functionalCount,
                    offlineMeters: meters.length - functionalCount,
                    readingMeters: functionalCount,
                };
            }),
            catchError(() => of({
                totalMeters: 0,
                onlineMeters: 0,
                offlineMeters: 0,
                readingMeters: 0,
            }))
        );
    }

    /**
     * Get all meters for dashboard table
     */
    getMeters(): Observable<Meter[]> {
        return combineLatest([
            this.meterApi.getAllMetersInfo(),
            this.loopStatusService.status$
        ]).pipe(
            map(([apiMeters, status]) => apiMeters.map(m => this.mapApiMeterToMeter(m, status))),
            catchError(() => of([]))
        );
    }

    /**
     * Get meter by ID with full details
     */
    getMeterById(id: number): Observable<MeterDetail | null> {
        return combineLatest([
            this.meterApi.getAllMetersInfo(),
            this.loopStatusService.status$
        ]).pipe(
            map(([meters, status]) => {
                const meter = meters.find(m => m.meter_id === id);
                if (!meter) return null;

                const mappedMeter = this.mapApiMeterToMeter(meter, status);
                return {
                    ...mappedMeter,
                    meterInfo: {
                        imei: this.formatSerialNumber(meter.serial_number, String(meter.meter_id)),
                        location: 'N/A',
                        installDate: undefined,
                        lastReadDate: new Date(),
                        totalRecords: 0,
                    },
                    operatingParams: [],
                    periodicReadings: [],
                    finalizedReadings: [],
                } as MeterDetail;
            }),
            catchError(() => of(null))
        );
    }

    /**
     * Get operating params from API query
     */
    getOperatingParams(meterId: number, startDate: Date, endDate: Date): Observable<MeterOperatingParams[]> {
        return this.meterApi.queryDataByTimeRange({
            meter_id: meterId,
            columns: ['phase_a_voltage', 'phase_b_voltage', 'phase_c_voltage', 'frequency', 'p_total', 'q_total', 'power_factor'],
            time_range: {
                start_utc: startDate.toISOString(),
                end_utc: endDate.toISOString()
            },
            limit: 1000,
            order: 'desc',
            interval_seconds: 180
        }).pipe(
            map(response => response.data.map(point => this.mapDataPointToOperatingParams(point))),
            catchError(() => of([]))
        );
    }

    /**
     * Get latest readings
     */
    getLatestReadings(meterId: number, numPoints: number = 10): Observable<MeterOperatingParams[]> {
        return this.meterApi.queryLatestReading({
            meter_id: meterId,
            columns: ['phase_a_voltage', 'phase_b_voltage', 'phase_c_voltage', 'frequency', 'p_total', 'q_total', 'power_factor', 'total_import_kwh'],
            count: numPoints,
            order: 'desc',
            interval_seconds: 1
        }).pipe(
            map(response => response.data.map(point => this.mapDataPointToOperatingParams(point))),
            catchError(() => of([]))
        );
    }

    /**
     * Get power history for charts
     */
    getPowerHistory(meterId: number, fromDate: Date, toDate: Date): Observable<MeterOperatingParams[]> {
        return this.getOperatingParams(meterId, fromDate, toDate);
    }

    /**
     * Map API MeterInfo to internal Meter interface
     */
    private mapApiMeterToMeter(apiMeter: ApiMeterInfo, status: MeterLoopStatus): Meter {
        return {
            id: apiMeter.meter_id,
            serialNumber: this.formatSerialNumber(apiMeter.serial_number, String(apiMeter.meter_id)),
            meterPointName: apiMeter.meter_name || `Meter ${apiMeter.meter_id}`,
            meterPointCode: `M-${apiMeter.meter_id}`,
            meterType: apiMeter.model || 'EDMI',
            meterKind: apiMeter.type || '',
            outstationNumber: apiMeter.outstation != null ? String(apiMeter.outstation) : '',
            multiplier: 1,
            surveyTypes: this.normalizeSurveyTypes(apiMeter.survey_type),
            roleId: apiMeter.role ?? null,
            sourceId: apiMeter.source_id ?? null,
            connectionStatus: this.resolveConnectionStatus(apiMeter, status),
            readingStatus: 'completed',
            lastUpdated: new Date(),
        };
    }

    private formatSerialNumber(value: ApiMeterInfo['serial_number'], fallback = ''): string {
        return value != null ? String(value) : fallback;
    }

    private normalizeSurveyTypes(value: ApiMeterInfo['survey_type']): string[] {
        if (Array.isArray(value)) {
            return value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0);
        }
        if (typeof value === 'string') {
            return value
                .split(',')
                .map(item => item.trim())
                .filter(Boolean);
        }
        return [];
    }

    private resolveConnectionStatus(apiMeter: ApiMeterInfo, status: MeterLoopStatus): Meter['connectionStatus'] {
        if (!status.isRunning) {
            return 'not_connected';
        }
        const entry = status.meterStatusById[apiMeter.meter_id];
        if (entry) {
            return mapRawStatusToConnection(entry.status, true);
        }
        return status.functionalIds.includes(apiMeter.meter_id) ? 'connected' : 'failed';
    }

    private countConnectedMeters(status: MeterLoopStatus): number {
        const entries = Object.values(status.meterStatusById);
        if (entries.length) {
            return entries.filter(entry => mapRawStatusToConnection(entry.status, true) === 'connected').length;
        }
        return status.functionalIds.length;
    }

    /**
     * Map API data point to operating params
     */
    private mapDataPointToOperatingParams(point: MeterDataPoint): MeterOperatingParams {
        return {
            timestamp: new Date(point['time_stamp']),
            phase: 'A',
            voltage: point['phase_a_voltage'] || 0,
            current: 0,
            activePower: point['p_total'] || 0,
            reactivePower: point['q_total'] || 0,
            powerFactor: point['power_factor'] || 1,
            frequency: point['frequency'] || 50,
        };
    }
}
