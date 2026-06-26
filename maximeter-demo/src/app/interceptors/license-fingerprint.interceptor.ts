import { HttpInterceptorFn } from '@angular/common/http';

export const licenseFingerprintInterceptor: HttpInterceptorFn = (req, next) => {
    const fingerprint = localStorage.getItem('mdm-license-fingerprint') || '';
    const isApiRequest = req.url.startsWith('/api') || req.url.includes('/api/');

    if (!fingerprint || !isApiRequest || req.headers.has('X-License-Fingerprint')) {
        return next(req);
    }

    return next(
        req.clone({
            setHeaders: {
                'X-License-Fingerprint': fingerprint,
            },
        })
    );
};
