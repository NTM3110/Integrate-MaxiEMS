import {Component, Input} from '@angular/core';
import {CommonModule} from '@angular/common';

@Component({
    selector: 'app-logo',
    standalone: true,
    imports: [CommonModule],
    template: `
        <div class="logo-container" [class]="size">
            <img [src]="logoSrc" alt="MAXiMeter Logo" class="logo-img"/>
            <span class="logo-text" *ngIf="showText">Advanced Metering Infrastructure System</span>
        </div>
    `,
    styles: [`
      .logo-container {
        display: flex;
        align-items: center;
        gap: 10px;

        &.small .logo-img {
          height: 28px;
        }

        &.medium .logo-img {
          height: 36px;
        }

        &.large .logo-img {
          height: 48px;
        }
      }

      .logo-img {
        height: 36px;
        width: auto;
        object-fit: contain;
      }

      .logo-text {
        font-size: 1rem;
        font-weight: 600;
        color: var(--primary-color);
        white-space: nowrap;
      }
    `],
})
export class LogoComponent {
    @Input() size: 'small' | 'medium' | 'large' = 'medium';
    @Input() showText = true;

    logoSrc = 'assets/images/MAXiMeter_Logo.png';
}
