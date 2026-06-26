import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = (route, state) => {
    const authService = inject(AuthService);
    const router = inject(Router);

    if (authService.isLoggedIn) {
        return true;
    }

    return authService.restoreAnyLocalSession().pipe(
        map((restored) => {
            if (restored) {
                return true;
            }

            return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
        }),
        catchError(() => of(router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } })))
    );
};
