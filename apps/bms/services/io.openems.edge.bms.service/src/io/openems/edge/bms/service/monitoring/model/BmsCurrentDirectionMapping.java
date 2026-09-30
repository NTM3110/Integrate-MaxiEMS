package io.openems.edge.bms.service.monitoring.model;

import java.util.Locale;

/** Fixed per-string mapping from device current to the BMS domain convention. */
public enum BmsCurrentDirectionMapping {
	/** A positive raw current means discharge. */
	POSITIVE(1),
	/** A negative raw current means discharge. */
	NEGATIVE(-1);

	private final int multiplier;

	private BmsCurrentDirectionMapping(int multiplier) {
		this.multiplier = multiplier;
	}

	/** Returns positive discharge current and negative charge current. */
	public double toDomainCurrent(double rawCurrent) {
		return rawCurrent * this.multiplier;
	}

	public static BmsCurrentDirectionMapping parse(String value) {
		if (value == null) {
			throw new IllegalArgumentException("dischargeCurrentDirection is required");
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("dischargeCurrentDirection must be POSITIVE or NEGATIVE", e);
		}
	}
}
