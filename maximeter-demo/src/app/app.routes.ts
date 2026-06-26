import { Routes } from '@angular/router';
import { MainLayoutComponent } from './components/layout/main-layout/main-layout.component';
import { authGuard } from './guards/auth.guard';
import { licenseGuard } from './guards/license.guard';

export const routes: Routes = [
    {
        path: 'login',
        loadComponent: () =>
            import('./pages/login/login.component').then((m) => m.LoginComponent),
    },
    {
        path: 'license',
        redirectTo: 'login',
        pathMatch: 'full',
    },
    {
        path: '',
        component: MainLayoutComponent,
        canActivate: [licenseGuard, authGuard],
        children: [
            { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
            {
                path: 'dashboard',
                loadComponent: () =>
                    import('./pages/dashboard/dashboard.component').then(
                        (m) => m.DashboardComponent
                    ),
                data: { title: 'Dashboard' },
            },
            {
                path: 'meter/:id',
                loadComponent: () =>
                    import('./pages/meter-detail/meter-detail.component').then(
                        (m) => m.MeterDetailComponent
                    ),
                data: { title: 'Meter Detail' },
            },
            // Configuration routes (placeholder)
            {
                path: 'config/meters',
                loadComponent: () =>
                    import('./pages/config/meter-config/meter-config.component').then(
                        (m) => m.MeterConfigComponent
                    ),
                data: { title: 'Serial / TCP Configuration' },
            },
            {
                path: 'config/profile',
                loadComponent: () =>
                    import('./pages/config/profile-config/profile-config.component').then(
                        (m) => m.ProfileConfigComponent
                    ),
                data: { title: 'Profile Setup' },
            },
            // Alerts routes (placeholder)
            {
                path: 'alerts',
                loadComponent: () =>
                    import('./pages/alerts/alerts.component').then(
                        (m) => m.AlertsComponent
                    ),
                data: { title: 'Alerts' },
            },
            // Account
            {
                path: 'account',
                loadComponent: () =>
                    import('./pages/account/account.component').then(
                        (m) => m.AccountComponent
                    ),
                data: { title: 'Account' },
            },
            // Energy Reports
            {
                path: 'energy-report',
                loadComponent: () =>
                    import('./pages/energy-report/energy-report.component').then(
                        (m) => m.EnergyReportComponent
                    ),
                data: { title: 'Energy Reports' },
            },
            {
                path: 'calculate-energy',
                loadComponent: () =>
                    import('./pages/calculate-energy/calculate-energy.component').then(
                        (m) => m.CalculateEnergyComponent
                    ),
                data: { title: 'Calculate Energy' },
            },
        ],
    },
    { path: '**', redirectTo: '' },
];
