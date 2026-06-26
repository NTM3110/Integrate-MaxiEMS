import { Component, Output, EventEmitter, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, ActivatedRoute, NavigationEnd } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../services/auth.service';
import { filter } from 'rxjs/operators';

@Component({
    selector: 'app-header',
    standalone: true,
    imports: [
        CommonModule,
        RouterModule,
        MatToolbarModule,
        MatIconModule,
        MatButtonModule,
        MatMenuModule,
        MatDividerModule,
        MatTooltipModule,
    ],
    templateUrl: './header.component.html',
    styleUrl: './header.component.scss',
})
export class HeaderComponent {
    @Output() menuToggle = new EventEmitter<void>();

    private authService = inject(AuthService);
    private router = inject(Router);
    private route = inject(ActivatedRoute);
    currentUser$ = this.authService.currentUser$;
    pageTitle = '';

    constructor() {
        this.router.events
            .pipe(filter(event => event instanceof NavigationEnd))
            .subscribe(() => {
                this.pageTitle = this.getRouteTitle(this.route);
            });
    }

    onMenuToggle(): void {
        this.menuToggle.emit();
    }

    navigateToAccount(): void {
        // TODO: Navigate to account
    }

    logout(): void {
        this.authService.logout();
    }

    private getRouteTitle(route: ActivatedRoute): string {
        let currentRoute: ActivatedRoute | null = route;
        let title = '';
        while (currentRoute?.firstChild) {
            currentRoute = currentRoute.firstChild;
            const dataTitle = currentRoute.snapshot.data['title'] as string | undefined;
            if (dataTitle) {
                title = dataTitle;
            }
        }
        return title;
    }
}
