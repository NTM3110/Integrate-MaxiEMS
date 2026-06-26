import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { PeriodBreakdown, FaultWindowResponse } from '../../../interfaces/api.interface';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

export interface MeterStatusDialogData {
    period: PeriodBreakdown;
    meterStatus: FaultWindowResponse | null;
    isLoading?: boolean;
    error?: string;
}

@Component({
    selector: 'app-meter-status-dialog',
    standalone: true,
    imports: [
        CommonModule,
        MatDialogModule,
        MatButtonModule,
        MatIconModule,
        MatTableModule,
        MatProgressSpinnerModule
    ],
    templateUrl: './meter-status-dialog.component.html',
    styleUrl: './meter-status-dialog.component.scss'
})
export class MeterStatusDialogComponent {
    displayedColumns: string[] = ['meter_serial', 'meter_name', 'scenario_code', 'fault_start_ts', 'fault_end_ts'];

    constructor(
        public dialogRef: MatDialogRef<MeterStatusDialogComponent>,
        @Inject(MAT_DIALOG_DATA) public data: MeterStatusDialogData
    ) { }

    close(): void {
        this.dialogRef.close();
    }

    getScenarioClass(code: string): string {
        if (!code || code === 'ALL_OK' || code === 'F01') {
            return 'fault-normal';
        }
        return 'fault-error';
    }

    formatScenarioCode(code: string | null | undefined): string {
        if (!code || code === 'ALL_OK') {
            return 'NORMAL';
        }
        if (code === 'NO_GRID') {
            return 'NO_MAIN';
        }
        if (code === 'NO_INTERCONNECT') {
            return 'NO_BACKUP';
        }
        if (code === 'NO_FAULT_DETAIL') {
            return 'NO SPECIFIC METER FAULT';
        }
        return code;
    }

    getTotalEnergy(period: any): number {
        return period.total_energy_to_lmv_kwh || period.its_to_lmv_kwh || 0;
    }
}
