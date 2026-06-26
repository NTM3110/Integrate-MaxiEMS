import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { MenuItem } from '../interfaces/menu-item.interface';

@Injectable({
    providedIn: 'root',
})
export class SidebarService {
    private isMobileSubject = new BehaviorSubject<boolean>(false);
    private isCollapsedSubject = new BehaviorSubject<boolean>(false);

    public isMobile$ = this.isMobileSubject.asObservable();
    public isCollapsed$ = this.isCollapsedSubject.asObservable();

    constructor() {
        this.checkScreenSize();
        if (typeof window !== 'undefined') {
            window.addEventListener('resize', () => this.checkScreenSize());
        }
    }

    private checkScreenSize(): void {
        if (typeof window !== 'undefined') {
            this.isMobileSubject.next(window.innerWidth < 768);
        }
    }

    public setIsMobile(isMobile: boolean): void {
        this.isMobileSubject.next(isMobile);
    }

    public toggleCollapsed(): void {
        this.isCollapsedSubject.next(!this.isCollapsedSubject.value);
    }

    public setCollapsed(collapsed: boolean): void {
        this.isCollapsedSubject.next(collapsed);
    }

    /**
     * Get menu items for sidebar
     */
    getMenuItems(): MenuItem[] {
        return [
            {
                label: 'Dashboard',
                icon: 'dashboard',
                route: '/dashboard',
            },
            {
                label: 'Energy Reports',
                icon: 'assessment',
                route: '/energy-report',
            },
            {
                label: 'Calculate Energy',
                icon: 'functions',
                route: '/calculate-energy',
            },
            {
                label: 'Configuration',
                icon: 'settings',
                children: [
                    {
                        label: 'Meter Management',
                        icon: 'electric_meter',
                        route: '/config/meters',
                    },
                    {
                        label: 'Profile Setup',
                        icon: 'tune',
                        route: '/config/profile',
                    },
                ],
            },
            {
                label: 'Alerts',
                icon: 'notifications',
                route: '/alerts',
            },
        ];
    }
}
