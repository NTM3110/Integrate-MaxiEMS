import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration, ChartType, Chart } from 'chart.js';
import { Subscription, catchError, finalize, interval, of } from 'rxjs';
import zoomPlugin from 'chartjs-plugin-zoom';
import 'hammerjs';

Chart.register(zoomPlugin);

import { MeterService } from '../../services/meter.service';
import { MeterApiService } from '../../services/meter-api.service';
import { MeterLoopStatusService } from '../../services/meter-loop-status.service';
import { MeterDetail, MeterOperatingParams } from '../../interfaces/meter.interface';
import { MeterDataPoint, COLUMN_DEFINITIONS, ColumnDefinition, MeterLoopStatusResponse } from '../../interfaces/api.interface';
import * as XLSX from 'xlsx';

@Component({
    selector: 'app-meter-detail',
    standalone: true,
    imports: [
        CommonModule,
        ReactiveFormsModule,
        MatCardModule,
        MatTabsModule,
        MatTableModule,
        MatIconModule,
        MatButtonModule,
        MatProgressSpinnerModule,
        MatFormFieldModule,
        MatDatepickerModule,
        MatNativeDateModule,
        MatInputModule,
        MatSelectModule,
        MatDividerModule,
        MatTooltipModule,
        MatSnackBarModule,
        BaseChartDirective,
    ],
    templateUrl: './meter-detail.component.html',
    styleUrl: './meter-detail.component.scss',
})
export class MeterDetailComponent implements OnInit, OnDestroy {
    readonly maxOperationColumns = 10;
    private readonly statusPollIntervalMs = 30000;
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private meterService = inject(MeterService);
    private meterApi = inject(MeterApiService);
    private loopStatusService = inject(MeterLoopStatusService);
    private snackBar = inject(MatSnackBar);
    private statusPollSubscription: Subscription | null = null;

    meter: MeterDetail | null = null;
    isLoading = true;
    isLoadingOperatingData = false;
    isLoadingProfileData = false;
    selectedTabIndex = 0;
    meterId: number = 0;

    // API data
    apiDataPoints: MeterDataPoint[] = [];
    latestRatios: MeterDataPoint | null = null;

    // Date filters
    fromDate = new FormControl(new Date(Date.now() - 24 * 60 * 60 * 1000)); // Last 24 hours
    toDate = new FormControl(new Date());
    fromTime = new FormControl('00:00', [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]);
    toTime = new FormControl(this.formatTimeValue(new Date()), [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]);

    // Selection controls
    columnControl = new FormControl<string[]>([
        'phase_a_voltage', 'phase_b_voltage', 'phase_c_voltage',
        'phase_a_current', 'phase_b_current', 'phase_c_current',
        'p_total', 'power_factor'
    ]);
    intervalControl = new FormControl<number>(60);
    loadSurveyControl = new FormControl<string>('LS01');
    loadColumnControl = new FormControl<string[]>([]);

    // Available options
    allColumns = COLUMN_DEFINITIONS;
    loadSurveyOptions: string[] = ['LS01', 'LS02', 'LS03'];
    loadProfileFields: string[] = [];
    intervals = [
        { label: '30s', value: 30 },
        { label: '1m', value: 60 },
        { label: '5m', value: 300 },
        { label: '15m', value: 900 },
        { label: '30m', value: 1800 },
        { label: '1h', value: 3600 },
    ];

    // Table columns
    operatingColumns: string[] = ['timestamp'];
    loadColumns: string[] = ['timestamp'];
    periodicColumns: string[] = ['timestamp', 'total_import_kwh', 'total_export_kwh', 'p_total', 'q_total'];
    finalizedColumns: string[] = ['timestamp', 'total_import_kwh', 'total_export_kwh'];

    // Chart 1: Voltage Chart
    operatingChartType: ChartType = 'line';
    operatingChartData: ChartConfiguration['data'] = { labels: [], datasets: [] };
    operatingChartOptions: ChartConfiguration['options'] = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: { position: 'top' },
            zoom: {
                pan: {
                    enabled: true,
                    mode: 'x',
                },
                zoom: {
                    wheel: {
                        enabled: true,
                    },
                    pinch: {
                        enabled: true
                    },
                    mode: 'x',
                }
            }
        },
        scales: {
            y: {
                beginAtZero: false,
                title: { display: false },
            },
            x: {
                title: { display: true, text: 'Time' },
            },
        },
    };

    // Chart 2: Power Chart
    loadChartType: ChartType = 'line';
    loadChartData: ChartConfiguration['data'] = { labels: [], datasets: [] };
    loadChartOptions: ChartConfiguration['options'] = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: { position: 'top' },
            zoom: {
                pan: {
                    enabled: true,
                    mode: 'x',
                },
                zoom: {
                    wheel: {
                        enabled: true,
                    },
                    pinch: {
                        enabled: true
                    },
                    mode: 'x',
                }
            }
        },
        scales: {
            y: {
                beginAtZero: false,
                title: { display: false },
            },
            x: {
                title: { display: true, text: 'Time' },
            },
        },
    };

    loadSurveyLabel = 'LS01';
    loadProfileDataPoints: MeterDataPoint[] = [];
    loadSamplingIntervalLabel = '';

    ngOnInit(): void {
        this.meterId = Number(this.route.snapshot.paramMap.get('id'));
        if (this.meterId) {
            this.startStatusPolling();
            this.loadMeterDetail(this.meterId);
            this.loadMeterData();
        }
    }

    ngOnDestroy(): void {
        this.statusPollSubscription?.unsubscribe();
    }

    loadMeterDetail(id: number): void {
        this.isLoading = true;
        this.meterService.getMeterById(id).subscribe({
            next: (meter) => {
                if (!meter) {
                    this.meter = null;
                    this.isLoading = false;
                    return;
                }
                this.meter = meter;
                this.configureLoadSurveyOptions(meter.surveyTypes);
                this.loadLatestRatios();
                this.isLoading = false;
            },
            error: () => {
                this.isLoading = false;
                this.snackBar.open('Error loading meter details', 'Close', { duration: 3000 });
            },
        });
    }

    loadMeterData(): void {
        if (this.isLoadingOperatingData || !this.validateTimeRange()) {
            return;
        }
        this.isLoadingOperatingData = true;
        const startDate = this.buildDateTime(this.fromDate.value, this.fromTime.value);
        const endDate = this.buildDateTime(this.toDate.value, this.toTime.value);
        const selectedColumns = this.columnControl.value || [];
        const intervalSeconds = this.intervalControl.value || 60;

        // Update table columns dynamically
        this.operatingColumns = ['timestamp', ...selectedColumns];

        this.meterApi.queryDataByTimeRange({
            meter_id: this.meterId,
            columns: selectedColumns,
            time_range: {
                start_utc: this.formatRequestDateTime(startDate),
                end_utc: this.formatRequestDateTime(endDate)
            },
            limit: 5000,
            order: 'asc',
            interval_seconds: intervalSeconds
        }).pipe(
            finalize(() => this.isLoadingOperatingData = false)
        ).subscribe({
            next: (response) => {
                this.apiDataPoints = response.data;
                this.loadLatestRatios();
                this.updateCharts();
            },
            error: (err) => {
                this.snackBar.open(`Error loading data: ${err.message}`, 'Close', { duration: 5000 });
            },
        });
    }

    getColumnInfo(columnName: string): ColumnDefinition | undefined {
        return COLUMN_DEFINITIONS.find(c => c.column_name === columnName);
    }

    isOperationColumnDisabled(columnName: string): boolean {
        const selectedColumns = this.columnControl.value || [];
        return selectedColumns.length >= this.maxOperationColumns && !selectedColumns.includes(columnName);
    }

    onOperatingColumnsChange(): void {
        const selectedColumns = this.columnControl.value || [];
        if (selectedColumns.length <= this.maxOperationColumns) {
            return;
        }
        this.columnControl.setValue(selectedColumns.slice(0, this.maxOperationColumns), { emitEvent: false });
        this.snackBar.open('A maximum of 10 columns can be selected.', 'Close', { duration: 3000 });
    }

    private loadLatestRatios(): void {
        this.meterApi.queryLatestReading({
            meter_id: this.meterId,
            columns: ['ct_ratio_primary', 'ct_ratio_secondary', 'vt_ratio_primary', 'vt_ratio_secondary'],
            count: 1,
            order: 'desc',
        }).subscribe({
            next: response => {
                this.latestRatios = response.data[0] || null;
            },
            error: () => {
                this.latestRatios = null;
            },
        });
    }

    private startStatusPolling(): void {
        this.refreshLoopStatus();
        this.statusPollSubscription = interval(this.statusPollIntervalMs).subscribe(() => this.refreshLoopStatus());
    }

    private refreshLoopStatus(): void {
        this.meterApi.getMeterLoopStatus().pipe(
            catchError(() => of(null))
        ).subscribe(response => {
            if (!response) {
                return;
            }
            this.applyLoopStatus(response);
        });
    }

    private applyLoopStatus(response: MeterLoopStatusResponse): void {
        const meterStatusById = Object.fromEntries(
            response.meter_status.map(entry => [entry.meter_id, entry])
        );
        this.loopStatusService.setStatus({
            isRunning: response.is_running,
            availableIds: response.functional_ids,
            functionalIds: response.functional_ids,
            meterStatusById,
            taskId: response.task_id,
            lastSlotTs: response.slot_ts,
        });
    }

    updateCharts(): void {
        if (this.apiDataPoints.length === 0) return;

        const labels = this.apiDataPoints.map(p => {
            const d = new Date(p['time_stamp']);
            return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
        });

        const selectedColumns = this.columnControl.value || [];
        const datasets: any[] = [];

        const colors = [
            '#dc3545', '#28a745', '#0d6efd', '#ffc107', '#17a2b8',
            '#6610f2', '#e83e8c', '#fd7e14', '#20c997', '#6f42c1'
        ];

        selectedColumns.forEach((colName, index) => {
            const info = this.getColumnInfo(colName);
            const color = colors[index % colors.length];

            if (info) {
                const label = info.unit ? `${info.description} (${info.unit})` : info.description;
                datasets.push({
                    data: this.apiDataPoints.map(p => typeof p[colName] === 'number' ? p[colName] : null),
                    label: label,
                    borderColor: color,
                    backgroundColor: `${color}1A`, // 10% opacity
                    fill: false,
                    tension: 0.3,
                    pointRadius: 2,
                });
            }
        });

        // Voltage Chart (reused for all Operating Parameters)
        this.operatingChartData = {
            labels,
            datasets
        };

        this.updateLoadChart();
    }

    loadProfileData(): void {
        if (this.isLoadingProfileData || !this.validateTimeRange()) {
            return;
        }
        this.isLoadingProfileData = true;
        const startDate = this.buildDateTime(this.fromDate.value, this.fromTime.value);
        const endDate = this.buildDateTime(this.toDate.value, this.toTime.value);
        const survey = this.loadSurveyControl.value || this.loadSurveyOptions[0] || 'LS01';

        this.meterApi.readProfile({
            meter_id: this.meterId,
            survey,
            from_datetime: this.formatRequestDateTime(startDate),
            to_datetime: this.formatRequestDateTime(endDate),
            max_records: 1000,
        }).pipe(
            finalize(() => this.isLoadingProfileData = false)
        ).subscribe({
            next: (response) => {
                this.loadProfileDataPoints = response.data || [];
                this.loadProfileFields = response.field || [];
                this.loadSurveyLabel = response.survey || survey;
                this.loadSamplingIntervalLabel = this.calculateSamplingInterval(this.loadProfileDataPoints);
                const selected = (this.loadColumnControl.value || [])
                    .filter(field => this.loadProfileFields.includes(field));
                const nextSelection = selected.length > 0 ? selected : this.loadProfileFields;
                this.loadColumnControl.setValue(nextSelection, { emitEvent: false });
                this.loadColumns = ['timestamp', ...nextSelection];
                this.updateLoadChart();
            },
            error: (err) => {
                this.snackBar.open(`Error loading load profile: ${err.message}`, 'Close', { duration: 5000 });
            },
        });
    }

    private configureLoadSurveyOptions(surveyTypes?: string[]): void {
        const normalized = (surveyTypes || [])
            .map(survey => survey.trim())
            .filter(Boolean);
        this.loadSurveyOptions = normalized.length ? normalized : ['LS01', 'LS02', 'LS03'];

        const selectedSurvey = this.loadSurveyControl.value;
        if (!selectedSurvey || !this.loadSurveyOptions.includes(selectedSurvey)) {
            this.loadSurveyControl.setValue(this.loadSurveyOptions[0], { emitEvent: false });
        }
    }

    private calculateSamplingInterval(points: MeterDataPoint[]): string {
        if (points.length < 2) return 'N/A';
        const first = new Date(points[0].time_stamp).getTime();
        const second = new Date(points[1].time_stamp).getTime();
        const diffSeconds = Math.abs(Math.round((second - first) / 1000));
        if (!diffSeconds) return 'N/A';
        if (diffSeconds % 3600 === 0) {
            const hours = diffSeconds / 3600;
            return `${hours}h`;
        }
        if (diffSeconds % 60 === 0) {
            const minutes = diffSeconds / 60;
            return `${minutes}m`;
        }
        return `${diffSeconds}s`;
    }

    updateLoadChart(): void {
        if (this.loadProfileDataPoints.length === 0) {
            this.loadChartData = { labels: [], datasets: [] };
            return;
        }

        const labels = this.loadProfileDataPoints.map(p => {
            const d = new Date(p['time_stamp']);
            return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
        });

        const datasets: any[] = [];
        const selectedFields = this.loadColumnControl.value || [];

        const colors = [
            '#28a745', '#dc3545', '#0d6efd', '#ffc107', '#17a2b8',
            '#6610f2', '#e83e8c', '#fd7e14', '#20c997', '#6f42c1'
        ];

        selectedFields.forEach((field, index) => {
            const color = colors[index % colors.length];
            datasets.push({
                data: this.loadProfileDataPoints.map(p => p[field] ?? 0),
                label: field,
                borderColor: color,
                backgroundColor: `${color}1A`,
                fill: false,
                tension: 0.3,
                pointRadius: 2,
            });
        });

        this.loadChartData = { labels, datasets };
    }

    applyFilter(): void {
        this.loadMeterData();
    }

    applyLoadProfileFilter(): void {
        this.loadProfileData();
    }

    exportExcel(type: string): void {
        const exportConfig = this.buildExportConfig(type);
        if (!exportConfig) {
            this.snackBar.open('Nothing to export yet. Load data first.', 'Close', { duration: 3000 });
            return;
        }

        const { filename, sheetName, rows } = exportConfig;
        const workbook = XLSX.utils.book_new();
        const worksheet = XLSX.utils.json_to_sheet(rows);
        XLSX.utils.book_append_sheet(workbook, worksheet, sheetName);

        const arrayBuffer = XLSX.write(workbook, { bookType: 'xlsx', type: 'array' });
        const blob = new Blob([arrayBuffer], {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        });

        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        link.click();
        window.URL.revokeObjectURL(url);

        this.snackBar.open('Excel exported successfully!', 'Close', { duration: 3000 });
    }

    goBack(): void {
        this.router.navigate(['/dashboard']);
    }

    formatDateTime(date: Date | string | undefined): string {
        if (!date) return '-';
        const d = new Date(date);
        const pad = (value: number) => String(value).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    }

    formatNumber(value: number | null | undefined, decimals = 2): string {
        if (value === undefined || value === null) return '-';
        return value.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
    }

    onLoadProfileColumnsChange(): void {
        this.loadColumns = ['timestamp', ...(this.loadColumnControl.value || [])];
        this.updateLoadChart();
    }

    private buildDateTime(dateValue: Date | null, timeValue: string | null): Date {
        const base = dateValue ? new Date(dateValue) : new Date();
        const [hours, minutes] = (timeValue || '00:00').split(':').map(Number);
        base.setHours(hours || 0, minutes || 0, 0, 0);
        return base;
    }

    private formatRequestDateTime(date: Date): string {
        const pad = (value: number) => String(value).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
    }

    private validateTimeRange(): boolean {
        if (this.fromTime.valid && this.toTime.valid) {
            return true;
        }
        this.snackBar.open('Enter time as HH:mm from 00:00 to 23:59.', 'Close', { duration: 4000 });
        return false;
    }

    private buildExportConfig(type: string): { filename: string; sheetName: string; rows: Record<string, unknown>[] } | null {
        const dateTag = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
        const safeName = (value: string) => value.replace(/[^a-zA-Z0-9-_]/g, '_');
        const meterName = safeName(this.meter?.meterPointName || `meter_${this.meterId}`);

        if (type === 'operating') {
            if (!this.apiDataPoints.length) {
                return null;
            }
            const columns = ['timestamp', ...(this.columnControl.value || [])];
            const rows = this.apiDataPoints.map(point => this.mapRow(point, columns));
            return {
                filename: `${meterName}_operating_${dateTag}.xlsx`,
                sheetName: 'Operating',
                rows,
            };
        }

        if (type === 'load') {
            if (!this.loadProfileDataPoints.length) {
                return null;
            }
            const columns = this.loadColumns.length ? this.loadColumns : ['timestamp', ...this.loadProfileFields];
            const rows = this.loadProfileDataPoints.map(point => this.mapRow(point, columns));
            const surveyLabel = safeName(this.loadSurveyLabel || 'survey');
            return {
                filename: `${meterName}_${surveyLabel}_${dateTag}.xlsx`,
                sheetName: 'LoadProfile',
                rows,
            };
        }

        if (type === 'finalized') {
            if (!this.apiDataPoints.length) {
                return null;
            }
            const columns = this.finalizedColumns;
            const rows = this.apiDataPoints.map(point => this.mapRow(point, columns));
            return {
                filename: `${meterName}_finalized_${dateTag}.xlsx`,
                sheetName: 'Finalized',
                rows,
            };
        }

        return null;
    }

    private mapRow(point: MeterDataPoint, columns: string[]): Record<string, unknown> {
        const row: Record<string, unknown> = {};
        for (const column of columns) {
            if (column === 'timestamp') {
                row[column] = point.time_stamp ? new Date(point.time_stamp).toISOString() : '';
                continue;
            }
            row[column] = point[column] ?? '';
        }
        return row;
    }

    private formatTimeValue(date: Date): string {
        return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
    }

}
