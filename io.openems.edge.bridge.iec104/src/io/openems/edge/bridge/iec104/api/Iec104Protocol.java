package io.openems.edge.bridge.iec104.api;

import java.util.HashMap;
import java.util.Map;

import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.ie.IeBinaryCounterReading;
import org.openmuc.j60870.ie.IeBinaryStateInformation;
import org.openmuc.j60870.ie.IeDoubleCommand;
import org.openmuc.j60870.ie.IeDoubleCommand.DoubleCommandState;
import org.openmuc.j60870.ie.IeDoublePointWithQuality;
import org.openmuc.j60870.ie.IeNormalizedValue;
import org.openmuc.j60870.ie.IeQualifierOfSetPointCommand;
import org.openmuc.j60870.ie.IeRegulatingStepCommand;
import org.openmuc.j60870.ie.IeRegulatingStepCommand.StepCommandState;
import org.openmuc.j60870.ie.IeScaledValue;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.IeValueWithTransientState;
import org.openmuc.j60870.ie.InformationElement;
import org.openmuc.j60870.ie.InformationObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.iec104.api.element.Iec104BitstringElement;
import io.openems.edge.bridge.iec104.api.element.Iec104CounterElement;
import io.openems.edge.bridge.iec104.api.element.Iec104DoublePointElement;
import io.openems.edge.bridge.iec104.api.element.Iec104Element;
import io.openems.edge.bridge.iec104.api.element.Iec104FloatElement;
import io.openems.edge.bridge.iec104.api.element.Iec104NormalizedValueElement;
import io.openems.edge.bridge.iec104.api.element.Iec104ScaledValueElement;
import io.openems.edge.bridge.iec104.api.element.Iec104SinglePointElement;
import io.openems.edge.bridge.iec104.api.element.Iec104StepPositionElement;

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
	 * <p>
	 * The ASDU Type Identification is matched against each element's registered
	 * type before parsing. Only elements whose type matches the ASDU's
	 * TypeIdentification are updated.
	 * 
	 * @param aSdu the {@link ASdu}
	 */
	public void handleAsdu(ASdu aSdu) {
		ASduType asduType = aSdu.getTypeIdentification();
		for (InformationObject io : aSdu.getInformationObjects()) {
			int ioa = io.getInformationObjectAddress();
			Iec104Element<?> element = this.elements.get(ioa);

			if (element == null) {
				continue;
			}

			// Type ID must match what the element expects
			if (element.getType() != asduType) {
				LOG.debug("ASduType mismatch for IOA [{}]: expected [{}], got [{}]", ioa,
						element.getType(), asduType);
				continue;
			}

			InformationElement[][] ies = io.getInformationElements();
			if (ies != null && ies.length > 0 && ies[0].length > 0) {
				InformationElement ie = ies[0][0];
				this.parseAndSetValue(element, ie, ioa, asduType);
			}
		}
	}

	/**
	 * Parses the InformationElement value and sets it on the Iec104Element.
	 * 
	 * <p>
	 * Parsing is dispatched based on the ASDU Type Identification, ensuring the
	 * correct InformationElement subtype is extracted.
	 * 
	 * @param element the target {@link Iec104Element}
	 * @param ie      the {@link InformationElement} from the ASDU
	 * @param ioa     the Information Object Address (for logging)
	 * @param asduType the {@link ASduType} for dispatch
	 */
	private void parseAndSetValue(Iec104Element<?> element, InformationElement ie, int ioa, ASduType asduType) {
		try {
			switch (asduType) {
			// ── Measurement: short float ─────────────────────────────────
			case M_ME_NC_1:
			case M_ME_TC_1:
				if (element instanceof Iec104FloatElement && ie instanceof IeShortFloat) {
					((Iec104FloatElement) element).setValue(((IeShortFloat) ie).getValue());
				}
				break;

			// ── Measurement: normalized value ────────────────────────────
			case M_ME_NA_1:
			case M_ME_TA_1:
			case M_ME_TD_1:
			case M_ME_ND_1:
				if (element instanceof Iec104NormalizedValueElement && ie instanceof IeNormalizedValue) {
					((Iec104NormalizedValueElement) element)
							.setValue((float) ((IeNormalizedValue) ie).getNormalizedValue());
				}
				break;

			// ── Measurement: scaled value ───────────────────────────────
			case M_ME_NB_1:
			case M_ME_TB_1:
			case M_ME_TE_1:
				if (element instanceof Iec104ScaledValueElement && ie instanceof IeScaledValue) {
					((Iec104ScaledValueElement) element)
							.setRawValue(((IeScaledValue) ie).getUnnormalizedValue());
				}
				break;

			// ── Single point ──────────────────────────────────────────────
			case M_SP_NA_1:
			case M_SP_TA_1:
			case M_SP_TB_1:
			case M_PS_NA_1:
				if (element instanceof Iec104SinglePointElement && ie instanceof IeSinglePointWithQuality) {
					((Iec104SinglePointElement) element).setValue(((IeSinglePointWithQuality) ie).isOn());
				}
				break;

			// ── Double point ─────────────────────────────────────────────
			case M_DP_NA_1:
			case M_DP_TA_1:
			case M_DP_TB_1:
				if (element instanceof Iec104DoublePointElement && ie instanceof IeDoublePointWithQuality) {
					((Iec104DoublePointElement) element)
							.setValue(((IeDoublePointWithQuality) ie).getDoublePointInformation().ordinal());
				}
				break;

			// ── Step position ──────────────────────────────────────────────
			case M_ST_NA_1:
			case M_ST_TA_1:
			case M_ST_TB_1:
				if (element instanceof Iec104StepPositionElement && ie instanceof IeValueWithTransientState) {
					((Iec104StepPositionElement) element).setValue(((IeValueWithTransientState) ie).getValue());
				}
				break;

			// ── Bitstring (32 bits) ───────────────────────────────────────
			case M_BO_NA_1:
			case M_BO_TA_1:
			case M_BO_TB_1:
				if (element instanceof Iec104BitstringElement && ie instanceof IeBinaryStateInformation) {
					((Iec104BitstringElement) element).setValue(((IeBinaryStateInformation) ie).getValue());
				}
				break;

			// ── Integrated totals (counter) ────────────────────────────────
			case M_IT_NA_1:
			case M_IT_TA_1:
			case M_IT_TB_1:
				if (element instanceof Iec104CounterElement && ie instanceof IeBinaryCounterReading) {
					((Iec104CounterElement) element)
							.setValue((long) ((IeBinaryCounterReading) ie).getCounterReading());
				}
				break;

			default:
				LOG.warn("Unhandled ASduType [{}] for IOA [{}]", asduType, ioa);
				break;
			}
		} catch (Exception e) {
			LOG.warn("Error parsing IOA [{}] with type [{}]: {}", ioa, asduType, e.getMessage());
		}
	}

	/**
	 * Creates a Command ASDU based on the element type and value.
	 * 
	 * <p>
	 * Uses the correct Type Identification for each command type:
	 * <ul>
	 * <li>Single command: C_SC_NA_1 (Type ID 45)
	 * <li>Double command: C_DC_NA_1 (Type ID 46)
	 * <li>Regulating step: C_RC_NA_1 (Type ID 47)
	 * <li>Set point normalized: C_SE_NA_1 (Type ID 48)
	 * <li>Set point scaled: C_SE_NB_1 (Type ID 50)
	 * <li>Set point short float: C_SE_NC_1 (Type ID 51)
	 * </ul>
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
				// C_SE_NC_1 (Type ID 51) — Set point command, short float
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
				// C_SC_NA_1 (Type ID 45) — Single command
				return new ASdu(ASduType.C_SC_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeSingleCommand((Boolean) value, 0, false) }
								})
						});
			}
		} else if (element instanceof Iec104DoublePointElement) {
			if (value instanceof Number) {
				// C_DC_NA_1 (Type ID 46) — Double command
				int cmdValue = ((Number) value).intValue();
				DoubleCommandState state = DoubleCommandState.getInstance(cmdValue);
				return new ASdu(ASduType.C_DC_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeDoubleCommand(state, 0, false) }
								})
						});
			}
		} else if (element instanceof Iec104StepPositionElement) {
			if (value instanceof Number) {
				// C_RC_NA_1 (Type ID 47) — Regulating step command
				int cmdValue = ((Number) value).intValue();
				StepCommandState state = StepCommandState.getInstance(cmdValue);
				return new ASdu(ASduType.C_RC_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeRegulatingStepCommand(state, 0, false) }
								})
						});
			}
		} else if (element instanceof Iec104NormalizedValueElement) {
			if (value instanceof Number) {
				// C_SE_NA_1 (Type ID 48) — Set point command, normalized value
				return new ASdu(ASduType.C_SE_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeNormalizedValue(((Number) value).intValue()),
											new IeQualifierOfSetPointCommand(0, false) }
								})
						});
			}
		} else if (element instanceof Iec104ScaledValueElement) {
			if (value instanceof Number) {
				// C_SE_NB_1 (Type ID 50) — Set point command, scaled value
				return new ASdu(ASduType.C_SE_NB_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeScaledValue(((Number) value).intValue()),
											new IeQualifierOfSetPointCommand(0, false) }
								})
						});
			}
		} else if (element instanceof Iec104BitstringElement) {
			if (value instanceof Number) {
				// C_BO_NA_1 (Type ID 7 mirrored for command) — Bitstring command
				return new ASdu(ASduType.C_BO_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0,
						commonAddress, new InformationObject[] {
								new InformationObject(ioa, new InformationElement[][] {
										{ new IeBinaryStateInformation(((Number) value).intValue()) }
								})
						});
			}
		}

		LOG.warn("Unsupported element type [{}] or value [{}] for IOA: {}",
				element.getClass().getSimpleName(), value, ioa);
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
