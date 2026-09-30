package io.openems.edge.pvinverter.api;

import io.openems.common.types.OptionsEnum;

public enum SystemCommand implements OptionsEnum {
	UNDEFINED(-1, "Undefined"),
	STOP(0, "Stop"),
	START(1, "Start");

	private final int value;
	private final String name;

	private SystemCommand(int value, String name) {
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
}
