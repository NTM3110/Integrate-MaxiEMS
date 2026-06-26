import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, retry, timer } from 'rxjs';
import { environment } from '../../environments/environment';

export interface LicenseStatus {
    activated: boolean;
    fingerprint?: string;
    user?: {
        id: string;
        email: string;
        name?: string;
    } | null;
    license?: {
        id: string;
        machineId: string;
        expiry?: string;
    } | null;
}

export interface LicenseActivationResponse {
    success: boolean;
    activated?: boolean;
    fingerprint?: string;
    message?: string;
}

@Injectable({
    providedIn: 'root',
})
export class LicenseService {
    private http = inject(HttpClient);
    private apiUrl = environment.apiUrl;

    status(): Observable<LicenseStatus> {
        const fingerprint = localStorage.getItem('mdm-license-fingerprint') || '';
        const headers = fingerprint
            ? new HttpHeaders({ 'X-License-Fingerprint': fingerprint })
            : new HttpHeaders();
        return this.http
            .get<LicenseStatus>(`${this.apiUrl}/api/license/status`, { headers })
            .pipe(retry({ count: 15, delay: () => timer(1000) }));
    }

    activate(licenseKey: string): Observable<LicenseActivationResponse> {
        return this.http.post<LicenseActivationResponse>(`${this.apiUrl}/api/license/activate`, {
            licenseKey,
        });
    }
}
