package io.openems.edge.inverter.ligoo;

import io.openems.common.types.OptionsEnum;

public enum LigooBatteryState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"),
	STANDBY(0, "Standby"),
	CC_CHARGE(1, "CC charge"),
	CV_CHARGE(2, "CV charge"),
	FLOAT(3, "Float"),
	FULL(4, "Full"),
	DISCHARGE(5, "Discharge"),
	FORCED_CHARGE(6, "Forced charge");

	private final int value;
	private final String name;

	private LigooBatteryState(int value, String name) {
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
		case 0 -> STANDBY.value;
		case 1 -> CC_CHARGE.value;
		case 2 -> CV_CHARGE.value;
		case 3 -> FLOAT.value;
		case 4 -> FULL.value;
		case 5 -> DISCHARGE.value;
		case 6 -> FORCED_CHARGE.value;
		default -> null;
		};
	}
}
