import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { MeterApiService } from '../../services/meter-api.service';
import {
    MonthlySummaryResponse,
    MonthlyBreakdownResponse,
    PeriodBreakdown,
    FaultWindowResponse,
    ApiMeterInfo
} from '../../interfaces/api.interface';
import { MeterStatusDialogComponent } from './meter-status-dialog/meter-status-dialog.component';
import { FormulaDialogComponent } from './formula-dialog/formula-dialog.component';

@Component({
    selector: 'app-energy-report',
    standalone: true,
    imports: [
        CommonModule,
        MatCardModule,
        MatTabsModule,
        MatTableModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatFormFieldModule,
        MatDialogModule,
        MatTooltipModule,
        MatSnackBarModule,
        FormsModule
    ],
    templateUrl: './energy-report.component.html',
    styleUrl: './energy-report.component.scss'
})
export class EnergyReportComponent implements OnInit, OnDestroy {
    private meterApi = inject(MeterApiService);
    private dialog = inject(MatDialog);
    private snackBar = inject(MatSnackBar);
    private subscriptions: Subscription[] = [];

    // Date selection
    currentYear: number = new Date().getFullYear();
    currentMonth: number = new Date().getMonth() + 1;
    years: number[] = [];
    months = [
        { value: 1, label: 'January' },
        { value: 2, label: 'February' },
        { value: 3, label: 'March' },
        { value: 4, label: 'April' },
        { value: 5, label: 'May' },
        { value: 6, label: 'June' },
        { value: 7, label: 'July' },
        { value: 8, label: 'August' },
        { value: 9, label: 'September' },
        { value: 10, label: 'October' },
        { value: 11, label: 'November' },
        { value: 12, label: 'December' }
    ];

    // Tab 1: Monthly Energy Data
    monthlySummary: MonthlySummaryResponse | null = null;
    summaryData: any | null = null;  // First item from the summary response
    monthlyBreakdown: MonthlyBreakdownResponse | null = null;
    isLoadingSummary = false;
    isLoadingBreakdown = false;
    isRecalculating = false;
    breakdownColumns: string[] = ['period_start', 'period_end', 'scenario_code', 'total_energy', 'grid_energy', 'k_factor', 'bess_energy', 'rts_energy', 'actions'];

    // Tab 2: Meter Status History
    selectedMeterId: number | null = null;
    meterStatusHistory: any[] = [];
    filteredHistory: any[] = [];
    isLoadingHistory = false;
    meters: ApiMeterInfo[] = []; // Full list from API
    availableMeters: { meter_id: number, serial_number: string }[] = []; // Dynamic list from faults

    ngOnInit(): void {
        // Generate year options (current year and 5 years back)
        const currentYear = new Date().getFullYear();
        for (let i = 0; i < 6; i++) {
            this.years.push(currentYear - i);
        }

        this.loadMonthlyData();
    }

    ngOnDestroy(): void {
        this.subscriptions.forEach(sub => sub.unsubscribe());
    }

    onDateChange(): void {
        this.loadMonthlyData();
    }

    loadMonthlyData(): void {
        this.loadMonthlySummary();
        this.loadMonthlyBreakdown();
        this.loadMonthlyFaults();
    }

    loadMonthlySummary(): void {
        this.isLoadingSummary = true;
        const sub = this.meterApi.getMonthlySummary(this.currentYear, this.currentMonth).subscribe({
            next: (data) => {
                this.monthlySummary = data;
                if (data && data.items && data.items.length > 0) {
                    this.summaryData = data.items[0];
                } else {
                    this.summaryData = null;
                }
                this.isLoadingSummary = false;
            },
            error: (error) => {
                console.error('Error loading monthly summary:', error);
                this.isLoadingSummary = false;
            }
        });
        this.subscriptions.push(sub);
    }

    loadMonthlyBreakdown(): void {
        this.isLoadingBreakdown = true;
        const sub = this.meterApi.getMonthlyBreakdown(this.currentYear, this.currentMonth).subscribe({
            next: (data) => {
                this.monthlyBreakdown = data;
                this.isLoadingBreakdown = false;
            },
            error: (error) => {
                console.error('Error loading monthly breakdown:', error);
                this.isLoadingBreakdown = false;
            }
        });
        this.subscriptions.push(sub);
    }

    loadMonthlyFaults(): void {
        this.isLoadingHistory = true;
        const sub = this.meterApi.getFaultsByMonth(this.currentYear, this.currentMonth).subscribe({
            next: (data) => {
                this.processMonthlyFaults(data);
                this.isLoadingHistory = false;
            },
            error: (error) => {
                console.error('Error loading monthly faults:', error);
                this.meterStatusHistory = [];
                this.filteredHistory = [];
                this.isLoadingHistory = false;
            }
        });
        this.subscriptions.push(sub);
    }

    processMonthlyFaults(data: FaultWindowResponse[]): void {
        if (!data || data.length === 0) {
            this.meterStatusHistory = [];
            this.filteredHistory = [];
            return;
        }

        // Flatten the response: Array of Windows -> Array of Fault Events
        const flattenedFaults: any[] = [];

        data.forEach(window => {
            if (window.faults && window.faults.length > 0) {
                window.faults.forEach(fault => {
                    flattenedFaults.push({
                        ...fault,
                        window_id: window.id,
                        scenario_code: fault.scenario_code || window.scenario_code,
                        window_start: window.window_start_ts,
                        window_end: window.window_end_ts
                    });
                });
            }
        });

        // Sort by fault start time (descending)
        this.meterStatusHistory = flattenedFaults.sort((a, b) =>
            new Date(b.fault_start_ts).getTime() - new Date(a.fault_start_ts).getTime()
        );

        this.updateAvailableMeters();
        this.filterHistory();
    }

    updateAvailableMeters(): void {
        const uniqueMeters = new Map<number, { meter_id: number, serial_number: string }>();

        this.meterStatusHistory.forEach(fault => {
            if (!uniqueMeters.has(fault.meter_id)) {
                uniqueMeters.set(fault.meter_id, {
                    meter_id: fault.meter_id,
                    serial_number: fault.meter_serial
                });
            }
        });

        // Convert map values to array and sort by serial number
        this.availableMeters = Array.from(uniqueMeters.values()).sort((a, b) =>
            a.serial_number.localeCompare(b.serial_number)
        );

        // Reset selection if the selected meter is no longer in the list
        if (this.selectedMeterId && !uniqueMeters.has(this.selectedMeterId)) {
            this.selectedMeterId = null;
        }
    }

    filterHistory(): void {
        if (!this.selectedMeterId) {
            this.filteredHistory = [...this.meterStatusHistory];
        } else {
            this.filteredHistory = this.meterStatusHistory.filter(fault =>
                fault.meter_id === this.selectedMeterId
            );
        }
    }

    isFaultyPeriod(period: any): boolean {
        // Consider any scenario code that is not 'ALL_OK' as faulty
        return period.scenario_code && period.scenario_code !== 'ALL_OK';
    }

    viewPeriodDetails(period: PeriodBreakdown): void {
        const dialogRef = this.dialog.open(MeterStatusDialogComponent, {
            panelClass: 'meter-status-dialog',
            data: {
                period: period,
                meterStatus: null,
                isLoading: true
            }
        });

        // Fetch details
        const sub = this.meterApi.getFaultsByWindow(period.period_start, period.period_end).subscribe({
            next: (data) => {
                dialogRef.componentInstance.data.meterStatus = data;
                dialogRef.componentInstance.data.isLoading = false;
            },
            error: (error) => {
                console.error('Error loading meter status:', error);
                dialogRef.componentInstance.data.isLoading = false;
                dialogRef.componentInstance.data.error = 'Failed to load details';
            }
        });
        this.subscriptions.push(sub);
    }

    openFormulaDialog(): void {
        this.dialog.open(FormulaDialogComponent, {
            panelClass: 'formula-dialog',
            autoFocus: false,
            restoreFocus: true,
        });
    }

    recalculateEnergy(): void {
        if (this.isRecalculating) {
            return;
        }

        this.isRecalculating = true;
        const sub = this.meterApi.recalculateProfileEnergy(this.currentYear, this.currentMonth).subscribe({
            next: (response) => {
                this.isRecalculating = false;
                this.loadMonthlyData();

                if (response.processed) {
                    this.snackBar.open(
                        `Energy recalculated from ${response.timestamp_count} profile timestamp(s).`,
                        'Close',
                        { duration: 4000 }
                    );
                } else {
                    this.snackBar.open(
                        `No recalculation completed: ${response.reason || 'no profile rows found'}.`,
                        'Close',
                        { duration: 5000 }
                    );
                }
            },
            error: (error) => {
                this.isRecalculating = false;
                this.snackBar.open(`Error recalculating energy: ${error.message}`, 'Close', { duration: 6000 });
            }
        });
        this.subscriptions.push(sub);
    }

    exportToCSV(): void {
        const start = new Date(this.currentYear, this.currentMonth - 1, 1);
        const end = new Date(this.currentYear, this.currentMonth, 1);

        const fromTs = this.formatDate(start);
        const toTs = this.formatDate(end);

        this.meterApi.downloadIntervalRawCSV(fromTs, toTs).subscribe({
            next: (blob) => {
                const url = window.URL.createObjectURL(blob);
                const link = document.createElement('a');
                link.href = url;
                link.download = `energy_report_${this.currentYear}_${this.currentMonth}.csv`;
                link.click();
                window.URL.revokeObjectURL(url);
            },
            error: (error) => {
                console.error('Error downloading CSV:', error);
            }
        });
    }

    private formatDate(date: Date): string {
        const year = date.getFullYear();
        const month = (date.getMonth() + 1).toString().padStart(2, '0');
        const day = date.getDate().toString().padStart(2, '0');
        const hours = date.getHours().toString().padStart(2, '0');
        const minutes = date.getMinutes().toString().padStart(2, '0');
        const seconds = date.getSeconds().toString().padStart(2, '0');
        return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`;
    }

    getScenarioClass(scenarioCode: string): string {
        if (!scenarioCode || scenarioCode === 'ALL_OK') {
            return 'fault-normal';
        }
        return 'fault-error';
    }

    formatScenarioCode(scenarioCode: string | null | undefined): string {
        if (!scenarioCode || scenarioCode === 'ALL_OK') {
            return 'NORMAL';
        }
        if (scenarioCode === 'NO_GRID') {
            return 'NO_MAIN';
        }
        if (scenarioCode === 'NO_INTERCONNECT') {
            return 'NO_BACKUP';
        }
        return scenarioCode;
    }

    getTotalEnergy(period: any): number {
        return period.interconnect_energy_kwh || 0;
    }
}
