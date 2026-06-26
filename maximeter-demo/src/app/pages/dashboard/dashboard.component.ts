import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription, interval, switchMap, catchError, of } from 'rxjs';
import { MeterApiService } from '../../services/meter-api.service';
import { MeterService } from '../../services/meter.service';
import { Meter, MeterStats } from '../../interfaces/meter.interface';
import {
    MeterLoopStatusService,
    MeterLoopStatus,
    mapRawStatusToConnection,
} from '../../services/meter-loop-status.service';
import { EnergyRole, EnergySource, MeterLoopStatusResponse } from '../../interfaces/api.interface';

interface MeterHealthEntry {
    id: number;
    serialNumber: string;
    name: string;
    statusLabel: string;
    connectionStatus: 'connected' | 'failed' | 'not_connected';
    lastUpdated: string;
    lastSlotTs: string;
    rawStatus: string;
}

const POLL_INTERVAL_MS = 30000; // poll every 5 seconds

@Component({
    selector: 'app-dashboard',
    standalone: true,
    imports: [
        CommonModule,
        MatCardModule,
        MatTableModule,
        MatIconModule,
        MatButtonModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
    ],
    templateUrl: './dashboard.component.html',
    styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit, OnDestroy {
    private meterApi = inject(MeterApiService);
    private meterService = inject(MeterService);
    private loopStatusService = inject(MeterLoopStatusService);
    private router = inject(Router);
    private pollSubscription: Subscription | null = null;
    private loopStatusSub: Subscription | null = null;

    stats: MeterStats | null = null;
    meters: Meter[] = [];
    isLoadingStats = true;
    isLoadingMeters = true;
    loopStatus: MeterLoopStatus = {
        isRunning: false,
        availableIds: [],
        functionalIds: [],
        meterStatusById: {},
        taskId: null,
        lastSlotTs: null,
    };
    healthMeters: MeterHealthEntry[] = [];
    roles: EnergyRole[] = [];
    sources: EnergySource[] = [];
    isHealthPanelCollapsed = false;

    displayedColumns: string[] = [
        'serialNumber',
        'meterPointName',
        'meterPointCode',
        'outstationNumber',
        'meterKind',
        'meterType',
        'role',
        'source',
        'connectionStatus',
    ];

    ngOnInit(): void {
        this.loadReferenceData();
        this.loopStatusSub = this.loopStatusService.status$.subscribe(status => {
            this.loopStatus = status;
            this.updateHealthMeters();
        });
        this.startPolling();
    }

    ngOnDestroy(): void {
        this.stopPolling();
        this.loopStatusSub?.unsubscribe();
    }

    loadStats(showLoader = true): void {
        if (showLoader) this.isLoadingStats = true;
        this.meterService.getMeterStats().subscribe({
            next: (stats) => {
                this.stats = stats;
                this.isLoadingStats = false;
            },
            error: () => {
                this.isLoadingStats = false;
            },
        });
    }

    loadMeters(showLoader = true): void {
        if (showLoader) this.isLoadingMeters = true;
        this.meterService.getMeters().subscribe({
            next: (meters) => {
                this.meters = meters;
                this.isLoadingMeters = false;
                this.updateHealthMeters();
            },
            error: () => {
                this.isLoadingMeters = false;
            },
        });
    }

    viewMeterDetail(meter: Meter): void {
        this.router.navigate(['/meter', meter.id]);
    }

    getConnectionStatusClass(status: string): string {
        return status === 'online' ? 'status-online' : 'status-offline';
    }

    getReadingStatusClass(status: string): string {
        return status === 'reading' ? 'status-reading' : 'status-done';
    }

    getConnectionStatusText(status: string): string {
        return status === 'online' ? 'Connected' : 'Disconnected';
    }

    getReadingStatusText(status: string): string {
        return status === 'reading' ? 'Reading' : 'Completed';
    }

    refreshDashboardData(showLoader = false): void {
        this.loadStats(showLoader);
        this.loadMeters(showLoader);
    }

    toggleHealthPanel(): void {
        this.isHealthPanelCollapsed = !this.isHealthPanelCollapsed;
    }

    get isLoading(): boolean {
        return this.isLoadingStats || this.isLoadingMeters;
    }


    getRoleLabel(roleId?: number | null): string {
        if (roleId == null) {
            return 'None';
        }
        return this.roles.find(role => role.id === roleId)?.name || `Role ${roleId}`;
    }

    getSourceLabel(sourceId?: number | null): string {
        if (sourceId == null) {
            return 'None';
        }
        return this.sources.find(source => source.id === sourceId)?.name || `Source ${sourceId}`;
    }

    private loadReferenceData(): void {
        this.meterApi.getRoles().subscribe({
            next: response => this.roles = response.items,
            error: () => this.roles = [],
        });
        this.meterApi.getSources().subscribe({
            next: response => this.sources = response.items,
            error: () => this.sources = [],
        });
    }
    private startPolling(): void {
        this.stopPolling();

        // Immediate first load with spinner
        this.refreshDashboardData(true);
        this.meterApi.getMeterLoopStatus().pipe(
            catchError(() => of(null))
        ).subscribe(response => {
            if (response) {
                this.applyPollResponse(response);
            }
        });

        // Background polling every POLL_INTERVAL_MS (no spinner)
        this.pollSubscription = interval(POLL_INTERVAL_MS).subscribe(() => {
            this.refreshDashboardData(false);
            this.meterApi.getMeterLoopStatus().pipe(
                catchError(() => of(null))
            ).subscribe(response => {
                if (response) {
                    this.applyPollResponse(response);
                }
            });
        });
    }

    private applyPollResponse(response: MeterLoopStatusResponse): void {
        const meterStatusById: Record<number, { meter_id: number; status: string; updated_at: string }> = {};
        for (const entry of response.meter_status) {
            meterStatusById[entry.meter_id] = entry;
        }

        this.loopStatusService.setStatus({
            isRunning: response.is_running,
            availableIds: response.functional_ids,
            functionalIds: response.functional_ids,
            meterStatusById,
            taskId: response.task_id,
            lastSlotTs: response.slot_ts ?? null,
        });
    }

    private stopPolling(): void {
        if (this.pollSubscription) {
            this.pollSubscription.unsubscribe();
            this.pollSubscription = null;
        }
    }

    private updateHealthMeters(): void {
        const statusIds = Object.keys(this.loopStatus.meterStatusById).map(id => Number(id));
        const meterIds = this.loopStatus.availableIds.length ? this.loopStatus.availableIds : statusIds;
        const meterMap = new Map(this.meters.map(meter => [meter.id, meter]));

        this.healthMeters = meterIds.map(id => {
            const entry = this.loopStatus.meterStatusById[id];
            const rawStatus = entry?.status || 'unknown';
            const connectionStatus = mapRawStatusToConnection(rawStatus, this.loopStatus.isRunning);
            const meterInfo = meterMap.get(id);
            const name = meterInfo?.meterPointName || `Meter ${id}`;
            const serialNumber = meterInfo?.serialNumber || '—';

            return {
                id,
                serialNumber,
                name,
                statusLabel: this.getStatusLabel(connectionStatus, rawStatus),
                connectionStatus,
                lastUpdated: this.formatTimestamp(entry?.updated_at),
                lastSlotTs: this.formatTimestamp(this.loopStatus.lastSlotTs ?? undefined),
                rawStatus,
            };
        });
    }

    private getStatusLabel(connection: MeterHealthEntry['connectionStatus'], rawStatus: string): string {
        if (connection === 'connected') {
            return 'Connected';
        }
        if (connection === 'failed') {
            return 'Failed';
        }
        return rawStatus !== 'unknown' ? rawStatus : 'Not Connected';
    }

    private formatTimestamp(value?: string): string {
        if (!value) {
            return '—';
        }
        const parsed = new Date(value);
        if (Number.isNaN(parsed.getTime())) {
            return value;
        }
        return parsed.toLocaleString();
    }
}
