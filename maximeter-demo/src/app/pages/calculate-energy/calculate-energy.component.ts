import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import {
    ApiMeterInfo,
    ReadProfilesForEnergyMeterResult,
    ReadProfilesForEnergyRequest,
    ReadProfilesForEnergyResponse,
} from '../../interfaces/api.interface';
import { MeterApiService } from '../../services/meter-api.service';

@Component({
    selector: 'app-calculate-energy',
    standalone: true,
    imports: [
        CommonModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatCardModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSnackBarModule,
    ],
    templateUrl: './calculate-energy.component.html',
    styleUrl: './calculate-energy.component.scss',
})
export class CalculateEnergyComponent implements OnInit {
    private fb = inject(FormBuilder);
    private meterApi = inject(MeterApiService);
    private snackBar = inject(MatSnackBar);

    isLoadingMeters = false;
    isReading = false;
    isRecalculating = false;

    meters: ApiMeterInfo[] = [];
    results: ReadProfilesForEnergyMeterResult[] = [];
    resultByMeterId = new Map<number, ReadProfilesForEnergyMeterResult>();
    lastResponse: ReadProfilesForEnergyResponse | null = null;

    form = this.fb.group({
        survey: ['LS01', Validators.required],
        from_datetime: [this.formatForInput(this.startOfToday()), Validators.required],
        to_datetime: [this.formatForInput(new Date()), Validators.required],
        max_records: [5, [Validators.required, Validators.min(1)]],
    });

    ngOnInit(): void {
        this.loadDefaults();
        this.loadMeters();
    }

    loadDefaults(): void {
        this.meterApi.getProfileConfig().subscribe({
            next: (config) => {
                this.form.patchValue({
                    survey: config.survey || 'LS01',
                    max_records: config.max_records || 5,
                });
            },
            error: () => {
                // Keep local defaults if the profile config endpoint is not ready.
            },
        });
    }

    loadMeters(): void {
        this.isLoadingMeters = true;
        this.meterApi.getAllMetersInfo().subscribe({
            next: (meters) => {
                this.meters = meters;
                this.isLoadingMeters = false;
            },
            error: (err) => {
                this.isLoadingMeters = false;
                this.snackBar.open(`Error loading meters: ${err.message}`, 'Close', { duration: 5000 });
            },
        });
    }

    readProfiles(): void {
        if (this.form.invalid || this.isReading) {
            this.form.markAllAsTouched();
            return;
        }

        const value = this.form.getRawValue();
        const from = new Date(String(value.from_datetime));
        const to = new Date(String(value.to_datetime));
        if (!(from < to)) {
            this.snackBar.open('End time must be after start time', 'Close', { duration: 4000 });
            return;
        }

        this.isReading = true;
        this.lastResponse = null;
        this.results = [];
        this.resultByMeterId.clear();

        const request: ReadProfilesForEnergyRequest = {
            survey: String(value.survey || '').trim(),
            from_datetime: String(value.from_datetime),
            to_datetime: String(value.to_datetime),
            max_records: Number(value.max_records),
        };

        this.meterApi.readProfilesForEnergy(request).subscribe({
            next: (response) => {
                this.isReading = false;
                this.lastResponse = response;
                this.results = response.results || [];
                this.resultByMeterId = new Map(this.results.map((item) => [item.meter_id, item]));
                this.snackBar.open(
                    `Profile read complete. Saved ${response.saved_rows} row(s).`,
                    'Close',
                    { duration: 5000 }
                );
            },
            error: (err) => {
                this.isReading = false;
                this.snackBar.open(`Error reading profiles: ${err.message}`, 'Close', { duration: 6000 });
            },
        });
    }

    recalculateEnergy(): void {
        if (this.isRecalculating) {
            return;
        }

        const from = new Date(String(this.form.getRawValue().from_datetime));
        if (Number.isNaN(from.getTime())) {
            this.snackBar.open('Select a valid start time', 'Close', { duration: 4000 });
            return;
        }

        const year = from.getFullYear();
        const month = from.getMonth() + 1;

        this.isRecalculating = true;
        this.meterApi.recalculateProfileEnergy(year, month).subscribe({
            next: (response) => {
                this.isRecalculating = false;
                if (response.processed) {
                    this.snackBar.open(
                        `Energy recalculated from ${response.timestamp_count} timestamp(s).`,
                        'Close',
                        { duration: 5000 }
                    );
                } else {
                    this.snackBar.open(
                        `No recalculation completed: ${response.reason || 'no profile rows found'}.`,
                        'Close',
                        { duration: 5000 }
                    );
                }
            },
            error: (err) => {
                this.isRecalculating = false;
                this.snackBar.open(`Error recalculating energy: ${err.message}`, 'Close', { duration: 6000 });
            },
        });
    }

    getResult(meterId: number): ReadProfilesForEnergyMeterResult | undefined {
        return this.resultByMeterId.get(meterId);
    }

    getStatusLabel(meter: ApiMeterInfo): string {
        const result = this.getResult(meter.meter_id);
        if (!result) {
            return meter.serial_port_config_id == null ? 'No Port' : 'Ready';
        }
        if (result.status === 'ok') {
            return result.saved_count > 0 ? 'Saved' : 'No Rows';
        }
        return result.status.replace(/_/g, ' ');
    }

    getStatusClass(meter: ApiMeterInfo): string {
        const result = this.getResult(meter.meter_id);
        if (!result) {
            return meter.serial_port_config_id == null ? 'status-error' : 'status-idle';
        }
        if (result.status === 'ok' && result.saved_count > 0) {
            return 'status-ok';
        }
        if (result.status === 'ok') {
            return 'status-warn';
        }
        return 'status-error';
    }

    private startOfToday(): Date {
        const date = new Date();
        date.setHours(0, 0, 0, 0);
        return date;
    }

    private formatForInput(date: Date): string {
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return `${year}-${month}-${day}T${hours}:${minutes}`;
    }
}
