import { CommonModule } from '@angular/common';
import { Component, ChangeDetectorRef, inject } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../services/auth.service';

@Component({
    selector: 'app-login',
    standalone: true,
    imports: [
        CommonModule,
        ReactiveFormsModule,
        MatCardModule,
        MatFormFieldModule,
        MatInputModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
    ],
    templateUrl: './login.component.html',
    styleUrl: './login.component.scss',
})
export class LoginComponent {
    private fb = inject(FormBuilder);
    private router = inject(Router);
    private route = inject(ActivatedRoute);
    private authService = inject(AuthService);
    private cdr = inject(ChangeDetectorRef);

    stage: 'login' | 'activate' = 'login';
    authenticatedUser: { id: string; email: string; name?: string } | null = null;

    loginForm: FormGroup = this.fb.group({
        email: ['', Validators.required],
        password: ['', Validators.required],
    });

    activateForm: FormGroup = this.fb.group({
        licenseKey: ['', Validators.required],
    });

    isLoading = false;
    hidePassword = true;
    errorMessage = '';
    returnUrl = '/dashboard';

    constructor() {
        this.returnUrl = this.route.snapshot.queryParams['returnUrl'] || '/dashboard';

        if (this.authService.isLoggedIn) {
            this.router.navigate([this.returnUrl]);
            return;
        }

        this.resumeLocalActivation();
    }

    onLogin(): void {
        if (this.loginForm.invalid) {
            return;
        }

        this.isLoading = true;
        this.errorMessage = '';
        const { email, password } = this.loginForm.value;

        this.authService.authenticate(email, password).subscribe({
            next: (response) => {
                this.isLoading = false;
                if (response.success && response.user) {
                    const user = response.user;
                    const savedKey = localStorage.getItem('demo_license_key');
                    const fingerprint = this.authService.licenseFingerprint;

                    if (fingerprint) {
                        this.verifyExistingLicense(user, fingerprint, savedKey);
                        return;
                    }

                    this.authenticatedUser = user;
                    if (savedKey) {
                        this.autoActivate(savedKey);
                        return;
                    }

                    this.stage = 'activate';
                    this.cdr.detectChanges();
                    return;
                }

                this.errorMessage = response.message || 'Authentication failed';
                this.cdr.detectChanges();
            },
            error: (error) => {
                this.isLoading = false;
                this.errorMessage = error?.error?.message || 'An error occurred. Please try again.';
                this.cdr.detectChanges();
            },
        });
    }

    onActivate(): void {
        if (this.activateForm.invalid) {
            return;
        }

        this.isLoading = true;
        this.errorMessage = '';
        const { licenseKey } = this.activateForm.value;

        this.authService.activate(licenseKey).subscribe({
            next: (response) => {
                this.isLoading = false;
                if (response.success) {
                    this.router.navigate([this.returnUrl]);
                    return;
                }
                this.errorMessage = response.message || 'Activation failed';
                this.cdr.detectChanges();
            },
            error: (error) => {
                this.isLoading = false;
                this.errorMessage = error?.error?.message || 'An error occurred. Please try again.';
                this.cdr.detectChanges();
            },
        });
    }

    private verifyExistingLicense(
        user: { id: string; email: string; name?: string },
        fingerprint: string,
        savedKey: string | null
    ): void {
        this.isLoading = true;
        this.authService.verifyLicense(fingerprint).subscribe({
            next: (verification) => {
                this.isLoading = false;
                if (verification.valid) {
                    this.authService.startAuthSession(user);
                    this.router.navigate([this.returnUrl]);
                    return;
                }

                if (savedKey) {
                    this.authenticatedUser = user;
                    this.autoActivate(savedKey);
                    return;
                }

                this.authenticatedUser = user;
                this.stage = 'activate';
                this.errorMessage = verification.message || 'License expired. Please reactivate.';
                this.cdr.detectChanges();
            },
            error: () => {
                this.isLoading = false;
                this.authService.startAuthSession(user);
                this.router.navigate([this.returnUrl]);
            },
        });
    }

    private resumeLocalActivation(): void {
        this.isLoading = true;
        this.errorMessage = '';

        this.authService.restoreAnyLocalSession().subscribe({
            next: (restored) => {
                this.isLoading = false;
                if (restored) {
                    this.router.navigate([this.returnUrl]);
                    return;
                }
                this.cdr.detectChanges();
            },
            error: () => {
                this.isLoading = false;
                this.cdr.detectChanges();
            },
        });
    }
    private autoActivate(savedKey: string): void {
        this.isLoading = true;
        this.errorMessage = '';
        this.authService.activate(savedKey).subscribe({
            next: (response) => {
                this.isLoading = false;
                if (response.success) {
                    this.authService.showMessage('License activated.', 'success');
                    this.router.navigate([this.returnUrl]);
                    return;
                }

                localStorage.removeItem('demo_license_key');
                this.stage = 'activate';
                this.errorMessage = response.message || 'Auto-activation failed. Please enter license key manually.';
                this.cdr.detectChanges();
            },
            error: (error) => {
                this.isLoading = false;
                localStorage.removeItem('demo_license_key');
                this.stage = 'activate';
                this.errorMessage = error?.error?.message || 'Auto-activation failed. Please enter license key manually.';
                this.cdr.detectChanges();
            },
        });
    }
}
