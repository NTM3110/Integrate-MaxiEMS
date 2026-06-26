import { Component, OnInit, Output, EventEmitter, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SidebarService } from '../../../services/sidebar.service';
import { MenuItem } from '../../../interfaces/menu-item.interface';
import { LogoComponent } from '../../logo/logo.component';

@Component({
    selector: 'app-sidebar',
    standalone: true,
    imports: [
        CommonModule,
        RouterModule,
        MatListModule,
        MatIconModule,
        MatExpansionModule,
        MatButtonModule,
        MatTooltipModule,
        LogoComponent,
    ],
    templateUrl: './sidebar.component.html',
    styleUrl: './sidebar.component.scss',
})
export class SidebarComponent implements OnInit {
    @Input() isCollapsed = false;
    @Output() linkClicked = new EventEmitter<void>();

    menuItems: MenuItem[] = [];
    isMobile = false;

    private sidebarService = inject(SidebarService);

    ngOnInit(): void {
        this.menuItems = this.sidebarService.getMenuItems();
        this.sidebarService.isMobile$.subscribe((isMobile) => {
            this.isMobile = isMobile;
        });
    }

    onLinkClick(): void {
        if (this.isMobile) {
            this.linkClicked.emit();
        }
    }
}
