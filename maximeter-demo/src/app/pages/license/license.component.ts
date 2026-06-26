import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { LicenseService } from '../../services/license.service';

interface LicenseActivationErrorDetail {
    code?: string;
    message?: string;
}

@Component({
    selector: 'app-license',
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
    templateUrl: './license.component.html',
    styleUrl: './license.component.scss',
})
export class LicenseComponent {
    private fb = inject(FormBuilder);
    private router = inject(Router);
    private licenseService = inject(LicenseService);
    private snackBar = inject(MatSnackBar);

    activationForm = this.fb.group({
        licenseKey: ['', Validators.required],
    });

    isLoading = false;
    statusText = '';
    errorMessage = '';
    projectId = '';

    constructor() {
        this.licenseService.status().subscribe({
            next: (status) => {
                this.projectId = '';
                if (status.activated) {
                    this.statusText = 'License activated.';
                    this.snackBar.open('License activated', 'Close', {
                        duration: 3000,
                        horizontalPosition: 'end',
                        verticalPosition: 'top',
                    });
                    window.setTimeout(() => this.router.navigate(['/login']), 500);
                    return;
                }
                this.statusText = '';
                this.errorMessage = '';
            },
            error: () => {
                this.errorMessage = 'Unable to check license status.';
            },
        });
    }

    activate(): void {
        if (this.activationForm.invalid) {
            return;
        }

        const value = this.activationForm.value;
        this.isLoading = true;
        this.errorMessage = '';
        this.statusText = '';

        this.licenseService.activate(value.licenseKey || '').subscribe({
            next: (response) => {
                this.isLoading = false;
                if (!response.success) {
                    this.errorMessage = response.message || 'Activation failed.';
                    return;
                }
                this.statusText = 'License activated.';
                this.snackBar.open('License activated', 'Close', {
                    duration: 3000,
                    horizontalPosition: 'end',
                    verticalPosition: 'top',
                });
                this.router.navigate(['/login']);
            },
            error: (error: unknown) => {
                this.isLoading = false;
                this.errorMessage = this.getActivationErrorMessage(error);
            },
        });
    }

    private getActivationErrorMessage(error: unknown): string {
        if (!(error instanceof HttpErrorResponse)) {
            return 'Activation failed.';
        }

        const detail = this.getErrorDetail(error);
        if (typeof detail === 'string') {
            return detail;
        }

        if (detail?.code === 'license_active_on_another_machine') {
            return detail.message || 'This license is already active on another machine.';
        }

        if (
            detail?.code === 'license_public_key_missing' ||
            detail?.code === 'license_public_key_unreadable'
        ) {
            return detail.message || 'License public key file is missing.';
        }

        return detail?.message || 'Activation failed.';
    }

    private getStatusErrorMessage(reason?: string): string {
        if (reason === 'not_activated') {
            return '';
        }

        if (reason === 'machine_changed') {
            return 'Invalid license. This license belongs to another device.';
        }

        if (reason === 'project_changed') {
            return 'Invalid license. This license is for another project.';
        }

        if (reason === 'expired') {
            return 'Invalid license. This license has expired.';
        }

        return reason ? 'Invalid license.' : '';
    }

    private getErrorDetail(error: HttpErrorResponse): string | LicenseActivationErrorDetail | undefined {
        const errorBody: unknown = error.error;
        if (!this.isRecord(errorBody)) {
            return undefined;
        }

        const detail = errorBody['detail'];
        if (typeof detail === 'string') {
            return detail;
        }

        if (!this.isRecord(detail)) {
            return undefined;
        }

        return {
            code: typeof detail['code'] === 'string' ? detail['code'] : undefined,
            message: typeof detail['message'] === 'string' ? detail['message'] : undefined,
        };
    }

    private isRecord(value: unknown): value is Record<string, unknown> {
        return typeof value === 'object' && value !== null;
    }
}
