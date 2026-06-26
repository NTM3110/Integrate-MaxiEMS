import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { LicenseService } from '../services/license.service';

export const licenseGuard: CanActivateFn = () => {
    const licenseService = inject(LicenseService);
    const router = inject(Router);

    return licenseService.status().pipe(
        map((status) => {
            if (status.activated) {
                return true;
            }

            return router.createUrlTree(['/login']);
        }),
        catchError(() => of(router.createUrlTree(['/login'])))
    );
};
