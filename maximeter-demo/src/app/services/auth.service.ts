import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, catchError, map, of, retry, switchMap, timer } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { environment } from '../../environments/environment';
import { User, LoginCredentials, LoginResponse, DEFAULT_PERMISSIONS } from '../interfaces/user.interface';

interface LicenseAuthResponse {
    success: boolean;
    message?: string;
    user?: {
        id: string;
        email: string;
        name?: string;
    };
}

interface LicenseActivateResponse {
    success: boolean;
    activated?: boolean;
    fingerprint?: string;
    message?: string;
    user?: {
        id: string;
        email: string;
        name?: string;
    };
    license?: {
        id: string;
        machineId: string;
        expiry?: string;
    };
}

interface LicenseStatusResponse {
    activated: boolean;
    fingerprint?: string;
    user?: {
        id: string;
        email: string;
        name?: string;
    } | null;
    license?: LicenseActivateResponse['license'] | null;
}
@Injectable({
    providedIn: 'root',
})
export class AuthService {
    private isLoggedInSubject = new BehaviorSubject<boolean>(false);
    private currentUserSubject = new BehaviorSubject<User | null>(null);
    private apiUrl = environment.apiUrl;

    public isLoggedIn$ = this.isLoggedInSubject.asObservable();
    public currentUser$ = this.currentUserSubject.asObservable();

    constructor(
        private http: HttpClient,
        private router: Router,
        private snackBar: MatSnackBar
    ) {
        this.checkSession();
    }

    public get isLoggedIn(): boolean {
        return this.isLoggedInSubject.value;
    }

    public get currentUser(): User | null {
        return this.currentUserSubject.value;
    }

    public get licenseFingerprint(): string {
        return localStorage.getItem('mdm-license-fingerprint') || '';
    }

    public get licenseHeaders(): HttpHeaders {
        const fingerprint = this.licenseFingerprint;
        return fingerprint ? new HttpHeaders({ 'X-License-Fingerprint': fingerprint }) : new HttpHeaders();
    }

    private checkSession(): void {
        const sessionActive = localStorage.getItem('mdm-session') === 'true';
        const storedUser = localStorage.getItem('mdm-user');
        const storedFingerprint = this.licenseFingerprint;

        if (!sessionActive || !storedUser || !storedFingerprint) {
            this.restoreAnyLocalSession().subscribe();
            return;
        }

        try {
            const user = JSON.parse(storedUser) as User;
            this.isLoggedInSubject.next(true);
            this.currentUserSubject.next(user);

            this.http
                .get<LicenseStatusResponse>(
                    `${this.apiUrl}/api/license/status`,
                    { headers: this.licenseHeaders }
                )
                .pipe(retry({ count: 15, delay: () => timer(1000) }))
                .subscribe({
                    next: (status) => {
                        if (!status.activated || status.fingerprint !== storedFingerprint) {
                            this.restoreBrowserLicenseSession().subscribe((restored) => {
                                if (!restored) {
                                    this.clearSession();
                                    this.router.navigate(['/login']);
                                }
                            });
                        }
                    },
                    error: () => {
                        this.restoreBrowserLicenseSession().subscribe((restored) => {
                            if (!restored) {
                                this.clearSession();
                                this.router.navigate(['/login']);
                            }
                        });
                    },
                });
        } catch {
            this.clearSession();
        }
    }

    authenticate(email: string, password: string): Observable<LicenseAuthResponse> {
        return this.http.post<LicenseAuthResponse>(`${this.apiUrl}/api/license/authenticate`, {
            email,
            password,
        });
    }

    verifyLicense(fingerprint: string): Observable<{ valid: boolean; code?: string; message?: string; expiry?: string }> {
        return this.http.post<{ valid: boolean; code?: string; message?: string; expiry?: string }>(
            `${this.apiUrl}/api/license/verify`,
            { fingerprint }
        );
    }

    restoreAnyLocalSession(): Observable<boolean> {
        return this.restoreLocalLicenseSession().pipe(
            switchMap((restored) => restored ? of(true) : this.restoreBrowserLicenseSession())
        );
    }
    restoreLocalLicenseSession(): Observable<boolean> {
        return this.http.get<LicenseStatusResponse>(`${this.apiUrl}/api/license/status`).pipe(
            retry({ count: 15, delay: () => timer(1000) }),
            map((status) => {
                if (!status.activated || !status.fingerprint || !status.user) {
                    return false;
                }

                this.startSession(this.createUser(status.user), status.fingerprint, status.license || undefined);
                return true;
            }),
            catchError(() => of(false))
        );
    }
    restoreBrowserLicenseSession(): Observable<boolean> {
        const fingerprint = this.licenseFingerprint;
        const user = this.readStorageJson<{ id: string; email: string; name?: string; username?: string; fullName?: string }>('mdm-user');
        const storedLicense = this.readStorageJson<{ fingerprint?: string; license?: LicenseActivateResponse['license'] }>('mdm-license-session');
        const licenseKey = localStorage.getItem('demo_license_key') || undefined;

        if (!fingerprint || !user) {
            return of(false);
        }

        return this.http.post<LicenseActivateResponse>(`${this.apiUrl}/api/license/restore-local`, {
            fingerprint,
            user,
            license: storedLicense?.license,
            licenseKey,
        }).pipe(
            map((response) => this.handleActivationResponse(response).success),
            catchError(() => of(false))
        );
    }
    startAuthSession(authUser: { id: string; email: string; name?: string }): void {
        const fingerprint = this.licenseFingerprint;
        if (!fingerprint) {
            return;
        }
        this.startSession(this.createUser(authUser), fingerprint, undefined);
    }

    activate(licenseKey: string, deviceName = 'MaxiMeter EDMI'): Observable<LoginResponse> {
        return this.http
            .post<LicenseActivateResponse>(`${this.apiUrl}/api/license/activate`, {
                licenseKey,
                deviceName,
            })
            .pipe(map((response) => this.handleActivationResponse(response)));
    }

    login(credentials: LoginCredentials): Observable<LoginResponse> {
        return this.http
            .post<LicenseActivateResponse>(`${this.apiUrl}/api/license/login`, {
                email: credentials.email,
                password: credentials.password,
                licenseKey: credentials.licenseKey,
                deviceName: 'MaxiMeter EDMI',
            })
            .pipe(map((response) => this.handleActivationResponse(response)));
    }

    logout(): void {
        if (this.licenseFingerprint) {
            this.http
                .post(`${this.apiUrl}/api/license/logout`, {}, { headers: this.licenseHeaders })
                .subscribe({ error: () => undefined });
        }
        this.clearSession();
        this.router.navigate(['/login']);
        this.showMessage('Logged out', 'info');
    }

    private readStorageJson<T>(key: string): T | null {
        const value = localStorage.getItem(key);
        if (!value) {
            return null;
        }
        try {
            return JSON.parse(value) as T;
        } catch {
            return null;
        }
    }
    private handleActivationResponse(response: LicenseActivateResponse): LoginResponse {
        if (response.success && response.activated && response.user && response.fingerprint) {
            const user = this.createUser(response.user);
            this.startSession(user, response.fingerprint, response.license);
            return { success: true, user, message: 'License activated' };
        }
        return {
            success: false,
            message: response.message || 'License activation failed',
        };
    }

    private createUser(authUser: { id: string; email: string; name?: string }): User {
        return {
            id: authUser.id,
            username: authUser.email,
            email: authUser.email,
            fullName: authUser.name || authUser.email,
            role: 'admin',
            enabled: true,
            lastLogin: new Date(),
            permissions: DEFAULT_PERMISSIONS['admin'],
        };
    }

    private startSession(
        user: User,
        fingerprint: string,
        license: LicenseActivateResponse['license']
    ): void {
        localStorage.setItem('mdm-session', 'true');
        localStorage.setItem('mdm-user', JSON.stringify(user));
        localStorage.setItem('mdm-license-fingerprint', fingerprint);
        localStorage.setItem('mdm-license-session', JSON.stringify({ fingerprint, license }));
        this.isLoggedInSubject.next(true);
        this.currentUserSubject.next(user);
    }

    private clearSession(): void {
        localStorage.removeItem('mdm-session');
        localStorage.removeItem('mdm-user');
        localStorage.removeItem('mdm-license-fingerprint');
        localStorage.removeItem('mdm-license-session');
        this.isLoggedInSubject.next(false);
        this.currentUserSubject.next(null);
    }

    public showMessage(message: string, type: 'success' | 'error' | 'warning' | 'info'): void {
        this.snackBar.open(message, 'Close', {
            duration: 4000,
            panelClass: [`${type}-snackbar`],
            horizontalPosition: 'end',
            verticalPosition: 'top',
        });
    }
}
