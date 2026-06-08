package io.openems.edge.bridge.iec104.api;

import java.util.HashMap;
import java.util.Map;

import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ie.InformationObject;
import org.openmuc.j60870.ie.IeDoublePointWithQuality;
import org.openmuc.j60870.ie.IeNormalizedValue;
import org.openmuc.j60870.ie.IeQualifierOfSetPointCommand;
import org.openmuc.j60870.ie.IeScaledValue;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.InformationElement;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.ASduType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.iec104.api.element.Iec104DoublePointElement;
import io.openems.edge.bridge.iec104.api.element.Iec104Element;
import io.openems.edge.bridge.iec104.api.element.Iec104FloatElement;
import io.openems.edge.bridge.iec104.api.element.Iec104NormalizedValueElement;
import io.openems.edge.bridge.iec104.api.element.Iec104ScaledValueElement;
import io.openems.edge.bridge.iec104.api.element.Iec104SinglePointElement;

public class Iec104Protocol {

	private static final Logger LOG = LoggerFactory.getLogger(Iec104Protocol.class);

	private final Map<Integer, Iec104Element<?>> elements = new HashMap<>();

	public Iec104Protocol(Iec104Element<?>... elements) {
		for (Iec104Element<?> element : elements) {
			this.elements.put(element.getIoa(), element);
		}
	}

	/**
	 * Parses an incoming ASDU and updates the mapped elements.
	 * 
	 * @param aSdu the {@link ASdu}
	 */
	public void handleAsdu(ASdu aSdu) {
		for (InformationObject io : aSdu.getInformationObjects()) {
			int ioa = io.getInformationObjectAddress();
			Iec104Element<?> element = this.elements.get(ioa);

			if (element != null) {
				InformationElement[][] ies = io.getInformationElements();
				if (ies != null && ies.length > 0 && ies[0].length > 0) {
					InformationElement ie = ies[0][0];
					this.parseAndSetValue(element, ie, ioa);
				}
			}
		}
	}

	/**
	 * Parses the InformationElement value and sets it on the Iec104Element.
	 * 
	 * @param element the target {@link Iec104Element}
	 * @param ie      the {@link InformationElement} from the ASDU
	 * @param ioa     the Information Object Address (for logging)
	 */
	private void parseAndSetValue(Iec104Element<?> element, InformationElement ie, int ioa) {
		try {
			if (element instanceof Iec104FloatElement) {
				// M_ME_NC_1 - Short Floating Point
				if (ie instanceof IeShortFloat) {
					((Iec104FloatElement) element).setValue(((IeShortFloat) ie).getValue());
				}

			} else if (element instanceof Iec104SinglePointElement) {
				// M_SP_NA_1 - Single Point
				if (ie instanceof IeSinglePointWithQuality) {
					((Iec104SinglePointElement) element).setValue(((IeSinglePointWithQuality) ie).isOn());
				}

			} else if (element instanceof Iec104ScaledValueElement) {
				// M_ME_NB_1 - Scaled Value
				if (ie instanceof IeScaledValue) {
					((Iec104ScaledValueElement) element).setRawValue(((IeScaledValue) ie).getUnnormalizedValue());
				} else if (ie instanceof IeShortFloat) {
					// Fallback: some slaves send float for scaled value addresses
					int intValue = Math.round(((IeShortFloat) ie).getValue());
					((Iec104ScaledValueElement) element).setValue(intValue);
				}

			} else if (element instanceof Iec104DoublePointElement) {
				// M_DP_NA_1 - Double Point
				if (ie instanceof IeDoublePointWithQuality) {
					((Iec104DoublePointElement) element)
							.setValue(((IeDoublePointWithQuality) ie).getDoublePointInformation().ordinal());
				}

			} else if (element instanceof Iec104NormalizedValueElement) {
				// M_ME_NA_1 - Normalized Value
				if (ie instanceof IeNormalizedValue) {
					((Iec104NormalizedValueElement) element)
							.setValue((float) ((IeNormalizedValue) ie).getNormalizedValue());
				}
			}
		} catch (Exception e) {
			LOG.warn("Error parsing IOA [{}]: {}", ioa, e.getMessage());
		}
	}

	/**
	 * Creates a Command ASDU based on the element type and value.
	 * 
	 * @param commonAddress the Common Address
	 * @param ioa           the Information Object Address
	 * @param value         the value to write
	 * @return the generated {@link ASdu}, or null if unsupported
	 */
	public ASdu createWriteCommand(int commonAddress, int ioa, Object value) {
		Iec104Element<?> element = this.elements.get(ioa);
		if (element == null) {
			LOG.warn("Cannot create write command for unmapped IOA: {}", ioa);
			return null;
		}

		if (element instanceof Iec104FloatElement) {
			if (value instanceof Number) {
				return new ASdu(ASduType.C_SE_NC_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeShortFloat(((Number) value).floatValue()),
												new IeQualifierOfSetPointCommand(0, false) }
								})
						});
			}
		} else if (element instanceof Iec104SinglePointElement) {
			if (value instanceof Boolean) {
				return new ASdu(ASduType.C_SC_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeSingleCommand((Boolean) value, 0, false) }
								})
						});
			}
		} else if (element instanceof Iec104ScaledValueElement || element instanceof Iec104NormalizedValueElement) {
			if (value instanceof Number) {
				return new ASdu(ASduType.C_SE_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeNormalizedValue(((Number) value).intValue()),
												new IeQualifierOfSetPointCommand(0, false) }
								})
						});
			}
		}

		LOG.warn("Unsupported element type or value for IOA: {}", ioa);
		return null;
	}

	/**
	 * Gets the number of mapped elements.
	 * 
	 * @return element count
	 */
	public int getElementCount() {
		return this.elements.size();
	}

}
