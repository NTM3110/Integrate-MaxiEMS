package io.openems.edge.bms.service.discharge.model;

/** Maps the signed device current to the BMS domain convention. */
public enum DischargeCurrentDirection {
	/** A positive raw current is discharge. */
	POSITIVE(1),
	/** A negative raw current is discharge. */
	NEGATIVE(-1);

	private final int multiplier;

	private DischargeCurrentDirection(int multiplier) {
		this.multiplier = multiplier;
	}

	/** Returns positive discharge current and negative charge current. */
	public double toDomainCurrent(double rawCurrent) {
		return rawCurrent * this.multiplier;
	}

	public static DischargeCurrentDirection parse(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Discharge current direction is missing.");
		}
		try {
			return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Discharge current direction must be POSITIVE or NEGATIVE.", e);
		}
	}
}
