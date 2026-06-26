import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { MeterStatusEntry } from '../interfaces/api.interface';

export interface MeterLoopStatus {
    isRunning: boolean;
    availableIds: number[];
    functionalIds: number[];
    meterStatusById: Record<number, MeterStatusEntry>;
    taskId: string | null;
    lastSlotTs?: string | null;
}

export type MeterConnectionStatus = 'connected' | 'failed' | 'not_connected';

export const mapRawStatusToConnection = (
    rawStatus: string | undefined,
    isRunning: boolean
): MeterConnectionStatus => {
    if (!isRunning) {
        return 'not_connected';
    }
    if (!rawStatus) {
        return 'not_connected';
    }
    const normalized = rawStatus.toLowerCase();
    if (normalized.includes('ok') || normalized.includes('connected')) {
        return 'connected';
    }
    if (normalized.includes('timeout') || normalized.includes('fail') || normalized.includes('error') || normalized.includes('denied')) {
        return 'failed';
    }
    return 'not_connected';
};

@Injectable({
    providedIn: 'root',
})
export class MeterLoopStatusService {
    private statusSubject = new BehaviorSubject<MeterLoopStatus>({
        isRunning: false,
        availableIds: [],
        functionalIds: [],
        meterStatusById: {},
        taskId: null,
        lastSlotTs: null,
    });

    status$ = this.statusSubject.asObservable();

    get snapshot(): MeterLoopStatus {
        return this.statusSubject.value;
    }

    setStatus(status: MeterLoopStatus): void {
        this.statusSubject.next(status);
    }

    setFromStartEvent(data: {
        task_id: string;
        available_ids: number[];
        functional_ids: number[];
        meter_status: MeterStatusEntry[];
    }): void {
        const meterStatusById = this.buildStatusMap(data.meter_status);
        this.statusSubject.next({
            isRunning: true,
            availableIds: data.available_ids,
            functionalIds: this.deriveFunctionalIds(meterStatusById, data.functional_ids),
            meterStatusById,
            taskId: data.task_id,
            lastSlotTs: null,
        });
    }

    updateMeterStatus(data: { task_id: string; meter_status: MeterStatusEntry[]; slot_ts?: string }): void {
        const snapshot = this.snapshot;
        const meterStatusById = this.buildStatusMap(data.meter_status, snapshot.meterStatusById);
        this.statusSubject.next({
            ...snapshot,
            isRunning: true,
            taskId: data.task_id || snapshot.taskId,
            meterStatusById,
            functionalIds: this.deriveFunctionalIds(meterStatusById, snapshot.functionalIds),
            lastSlotTs: data.slot_ts ?? snapshot.lastSlotTs ?? null,
        });
    }

    reset(): void {
        this.statusSubject.next({
            isRunning: false,
            availableIds: [],
            functionalIds: [],
            meterStatusById: {},
            taskId: null,
            lastSlotTs: null,
        });
    }

    private buildStatusMap(
        entries: MeterStatusEntry[],
        existing: Record<number, MeterStatusEntry> = {}
    ): Record<number, MeterStatusEntry> {
        const next = { ...existing };
        for (const entry of entries) {
            next[entry.meter_id] = entry;
        }
        return next;
    }

    private deriveFunctionalIds(
        meterStatusById: Record<number, MeterStatusEntry>,
        fallback: number[]
    ): number[] {
        const connectedIds = Object.values(meterStatusById)
            .filter(entry => mapRawStatusToConnection(entry.status, true) === 'connected')
            .map(entry => entry.meter_id);
        return connectedIds.length > 0 ? connectedIds : fallback;
    }
}
