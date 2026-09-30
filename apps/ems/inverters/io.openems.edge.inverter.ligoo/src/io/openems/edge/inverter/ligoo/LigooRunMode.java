package io.openems.edge.inverter.ligoo;

import io.openems.common.types.OptionsEnum;

public enum LigooRunMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"),
	SINGLE_PHASE_LOW_VOLTAGE_G1(1, "Single-phase low-voltage G1"),
	SINGLE_PHASE_LOW_VOLTAGE_G2(2, "Single-phase low-voltage G2"),
	SINGLE_PHASE_HIGH_VOLTAGE(11, "Single-phase high-voltage"),
	THREE_PHASE_LOW_VOLTAGE(21, "Three-phase low-voltage"),
	THREE_PHASE_HIGH_VOLTAGE_T2(31, "Three-phase high-voltage T2"),
	THREE_PHASE_HIGH_VOLTAGE_T3(32, "Three-phase high-voltage T3");

	private final int value;
	private final String name;

	private LigooRunMode(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}

	public static Integer toChannelValue(Integer value) {
		if (value == null) {
			return null;
		}
		return switch (value.intValue()) {
		case 1 -> SINGLE_PHASE_LOW_VOLTAGE_G1.value;
		case 2 -> SINGLE_PHASE_LOW_VOLTAGE_G2.value;
		case 11 -> SINGLE_PHASE_HIGH_VOLTAGE.value;
		case 21 -> THREE_PHASE_LOW_VOLTAGE.value;
		case 31 -> THREE_PHASE_HIGH_VOLTAGE_T2.value;
		case 32 -> THREE_PHASE_HIGH_VOLTAGE_T3.value;
		default -> null;
		};
	}
}
