import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

interface FormulaScenario {
    scenario: string;
    code: string;
    appliesWhen: string;
    kRule: string;
    rtsFormula: string;
    bessFormula: string;
}

@Component({
    selector: 'app-formula-dialog',
    standalone: true,
    imports: [
        CommonModule,
        MatButtonModule,
        MatDialogModule,
        MatIconModule,
    ],
    templateUrl: './formula-dialog.component.html',
    styleUrl: './formula-dialog.component.scss',
})
export class FormulaDialogComponent {
    readonly variables = [
        { name: 'E', description: 'Sum of export energy from all MAIN meters' },
        { name: 'E_LMV', description: 'Sum of import energy from all BACKUP meters' },
        { name: 'E_self', description: 'Sum of import energy from all SELF_USE meters' },
        { name: 'E_RTS', description: 'Total RTS export energy' },
        { name: 'E_BESS_charge', description: 'Total BESS charge energy' },
        { name: 'E_NORMAL_BESS_DIS', description: 'Total BESS discharge energy from normal BESS meters' },
        { name: 'K', description: 'Distribution factor. When all configured MAIN and BACKUP meters are present: max(0, (E - E_LMV) / E). Otherwise the previous K is reused.' },
    ];

    readonly scenarios: FormulaScenario[] = [
        {
            scenario: 'NORMAL',
            code: 'F01_NORMAL',
            appliesWhen: 'All configured MAIN, BACKUP, SELF_USE, RTS, and BESS data are available.',
            kRule: 'Calculate K from the summed MAIN and BACKUP totals.',
            rtsFormula: 'RTS_to_LMV = (E_RTS - E_BESS_charge - E_self) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E * (1 - K) - RTS_to_LMV',
        },
        {
            scenario: 'NO_MAIN',
            code: 'F02_NO_MAIN',
            appliesWhen: 'One or more configured MAIN meters are missing, while BACKUP, SELF_USE, RTS, and BESS data are complete.',
            kRule: 'Reuse previous K.',
            rtsFormula: 'RTS_to_LMV = (E_RTS - E_BESS_charge - E_self) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E_LMV - RTS_to_LMV',
        },
        {
            scenario: 'NO_BACKUP',
            code: 'F03_NO_BACKUP',
            appliesWhen: 'One or more configured BACKUP meters are missing, while MAIN and source data are usable.',
            kRule: 'Reuse previous K.',
            rtsFormula: 'RTS_to_LMV = (E_RTS - E_BESS_charge - E_self) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E * (1 - K) - RTS_to_LMV',
        },
        {
            scenario: 'RTS_FAULTY',
            code: 'F04_ONLY_RTS_FAULTY',
            appliesWhen: 'One or more RTS meters are missing or faulty.',
            kRule: 'Use current K when available, otherwise previous K.',
            rtsFormula: 'RTS_to_LMV = (E - E_NORMAL_BESS_DIS) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E_NORMAL_BESS_DIS * (1 - K)',
        },
        {
            scenario: 'NO_SELF',
            code: 'F05_NO_SELF',
            appliesWhen: 'SELF_USE meter data is missing.',
            kRule: 'Use current K when available, otherwise previous K.',
            rtsFormula: 'RTS_to_LMV = (E - E_NORMAL_BESS_DIS) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E_NORMAL_BESS_DIS * (1 - K)',
        },
        {
            scenario: 'BESS_FAULTY',
            code: 'F06_ONLY_BESS_FAULTY',
            appliesWhen: 'One or more BESS meters are missing or faulty.',
            kRule: 'Use current K when available, otherwise previous K.',
            rtsFormula: 'RTS_to_LMV = (E - E_NORMAL_BESS_DIS) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E_NORMAL_BESS_DIS * (1 - K)',
        },
        {
            scenario: 'BESS_RTS_FAULTY',
            code: 'F07_BOTH_BESS_RTS_FAULTY',
            appliesWhen: 'Both BESS and RTS meter groups have missing or faulty meters.',
            kRule: 'Use current K when available, otherwise previous K.',
            rtsFormula: 'RTS_to_LMV = (E - E_NORMAL_BESS_DIS) * (1 - K)',
            bessFormula: 'BESS_to_LMV = E_NORMAL_BESS_DIS * (1 - K)',
        },
        {
            scenario: 'INQUALIFIED',
            code: 'F99_INQUALIFIED',
            appliesWhen: 'The interval does not have enough usable meter data to calculate energy.',
            kRule: 'No K update.',
            rtsFormula: 'RTS_to_LMV = 0',
            bessFormula: 'BESS_to_LMV = 0',
        },
    ];

    readonly inqualifiedCases = [
        'MAIN is missing and BACKUP is missing.',
        'MAIN is missing and SELF_USE is missing.',
        'MAIN is missing and any BESS meter is missing or faulty.',
        'MAIN is missing and any RTS meter is missing or faulty.',
        'MAIN is missing with any combination of missing BACKUP, SELF_USE, BESS, or RTS data.',
    ];

    constructor(private dialogRef: MatDialogRef<FormulaDialogComponent>) {}

    close(): void {
        this.dialogRef.close();
    }
}
