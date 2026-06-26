import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-account',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatIconModule],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Account Settings</h1>
      </div>
      <mat-card class="placeholder-card">
        <mat-icon>person</mat-icon>
        <h2>Under Development</h2>
        <p>Account settings features will be available in the next version.</p>
      </mat-card>
    </div>
  `,
  styles: [`
    .placeholder-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 60px !important;
      text-align: center;
      
      mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--text-secondary);
        margin-bottom: 16px;
      }
      
      h2 {
        font-size: 1.25rem;
        font-weight: 600;
        margin: 0 0 8px;
      }
      
      p {
        color: var(--text-secondary);
        margin: 0;
      }
    }
  `],
})
export class AccountComponent { }
