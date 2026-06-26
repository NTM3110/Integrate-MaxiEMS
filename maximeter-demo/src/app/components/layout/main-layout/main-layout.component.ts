import { Component, ViewChild, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { MatSidenavModule, MatSidenav } from '@angular/material/sidenav';
import { Subscription } from 'rxjs';
import { HeaderComponent } from '../header/header.component';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { SidebarService } from '../../../services/sidebar.service';

@Component({
    selector: 'app-main-layout',
    standalone: true,
    imports: [
        CommonModule,
        RouterOutlet,
        MatSidenavModule,
        HeaderComponent,
        SidebarComponent,
    ],
    templateUrl: './main-layout.component.html',
    styleUrl: './main-layout.component.scss',
})
export class MainLayoutComponent implements OnInit, OnDestroy {
    @ViewChild('sidenav') sidenav!: MatSidenav;

    isMobile = false;
    isCollapsed = false;
    private subscriptions: Subscription[] = [];

    private breakpointObserver = inject(BreakpointObserver);
    private sidebarService = inject(SidebarService);

    ngOnInit(): void {
        const breakpointSub = this.breakpointObserver
            .observe([Breakpoints.XSmall, Breakpoints.Small])
            .subscribe((result) => {
                this.isMobile = result.matches;
                this.sidebarService.setIsMobile(this.isMobile);

                if (!this.isMobile) {
                    this.isCollapsed = false;
                }
            });
        this.subscriptions.push(breakpointSub);
    }

    ngOnDestroy(): void {
        this.subscriptions.forEach((sub) => sub.unsubscribe());
    }

    onMenuToggle(): void {
        if (this.isMobile) {
            this.sidenav.toggle();
        } else {
            this.isCollapsed = !this.isCollapsed;
            this.sidebarService.setCollapsed(this.isCollapsed);
        }
    }

    closeSidebar(): void {
        if (this.isMobile && this.sidenav) {
            this.sidenav.close();
        }
    }
}
