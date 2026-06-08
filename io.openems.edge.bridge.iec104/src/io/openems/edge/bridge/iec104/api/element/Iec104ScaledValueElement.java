package io.openems.edge.bridge.iec104.api.element;

/**
 * An Iec104ScaledValueElement represents a Scaled Value (e.g. M_ME_NB_1).
 * 
 * <p>
 * The raw value is a 16-bit signed integer. An optional scale factor can be
 * applied to convert the raw value to the desired engineering unit.
 */
public class Iec104ScaledValueElement extends Iec104Element<Integer> {

	private final double scaleFactor;

	/**
	 * Constructor with scale factor.
	 * 
	 * @param ioa         the Information Object Address
	 * @param scaleFactor the scale factor to apply (e.g. 0.1, 0.01)
	 */
	public Iec104ScaledValueElement(int ioa, double scaleFactor) {
		super(ioa);
		this.scaleFactor = scaleFactor;
	}

	/**
	 * Constructor without scale factor (scale = 1).
	 * 
	 * @param ioa the Information Object Address
	 */
	public Iec104ScaledValueElement(int ioa) {
		this(ioa, 1.0);
	}

	/**
	 * Sets the raw scaled value and applies the scale factor.
	 * 
	 * @param rawValue the raw signed integer value
	 */
	public void setRawValue(int rawValue) {
		int scaledValue = (int) Math.round(rawValue * this.scaleFactor);
		this.setValue(scaledValue);
	}

	/**
	 * Gets the scale factor.
	 * 
	 * @return the scale factor
	 */
	public double getScaleFactor() {
		return this.scaleFactor;
	}

}
