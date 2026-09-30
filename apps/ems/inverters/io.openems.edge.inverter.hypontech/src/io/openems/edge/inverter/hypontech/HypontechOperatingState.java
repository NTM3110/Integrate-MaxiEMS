package io.openems.edge.inverter.hypontech;

import io.openems.common.types.OptionsEnum;

public enum HypontechOperatingState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"),
	WAIT(0, "Wait"),
	NORMAL(1, "Normal"),
	FAULT(2, "Fault");

	private final int value;
	private final String name;

	private HypontechOperatingState(int value, String name) {
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
		case 0 -> WAIT.value;
		case 1 -> NORMAL.value;
		case 2 -> FAULT.value;
		default -> null;
		};
	}
}
