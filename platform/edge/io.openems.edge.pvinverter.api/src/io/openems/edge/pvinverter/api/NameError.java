package io.openems.edge.pvinverter.api;

public enum NameError {
	/**
	 * The AC breaker is open or the inverter is physically detached from the grid.
	 */
	AC_DISCONNECT,
	/**
	 * The DC switch/isolator is open.
	 */
	DC_DISCONNECT,
	/**
	 * Input voltage from PV strings exceeds the hardware limit.
	 */
	DC_OVER_VOLT,
	/**
	 * Loss of mains (LOM).
	 */
	GRID_DISCONNECT,
	/**
	 * Leakage current detected or insulation resistance (ISO) too low.
	 */
	GROUND_FAULT,
	/**
	 * Grid Frequency is too high.
	 */
	OVER_FREQ,
	/**
	 * Internal ambient or IGBT temperature exceeds safe threshold.
	 */
	OVER_TEMP,
	/**
	 * Generic AC Overvoltage (Grid instability).
	 */
	OVER_VOLT,
	/**
	 * Grid Frequency is too low.
	 */
	UNDER_FREQ,
	/**
	 * Generic AC Undervoltage (Brownout).
	 */
	UNDER_VOLT;

}
