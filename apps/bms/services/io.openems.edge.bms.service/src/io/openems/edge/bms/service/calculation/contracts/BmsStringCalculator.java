package io.openems.edge.bms.service.calculation.contracts;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;

import io.openems.edge.common.component.OpenemsComponent;

/**
 * Runtime boundary between the always-active BMS API bundle and a dedicated
 * per-string calculator component supplied by the calculator bundle.
 */
public interface BmsStringCalculator extends OpenemsComponent {
	BmsCalculationSnapshot latestSnapshot();
	String lastCalculationError();
	double cellCutoffVoltage();
	double cellFloatVoltage();
	int ratedResistanceUohm();
	int consecutiveSnapshotFailures();
}
