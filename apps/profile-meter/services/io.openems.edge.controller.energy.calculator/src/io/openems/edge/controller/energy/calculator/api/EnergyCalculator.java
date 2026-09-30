package io.openems.edge.controller.energy.calculator.api;

import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;

public interface EnergyCalculator {

	/**
	 * Calculates the energy report pipeline for a profile interval ending at the
	 * given timestamp.
	 * 
	 * @param intervalEnd the profile interval end timestamp
	 * @throws OpenemsNamedException on calculation error
	 */
	void calculateForIntervalEnding(Instant intervalEnd) throws OpenemsNamedException;
}