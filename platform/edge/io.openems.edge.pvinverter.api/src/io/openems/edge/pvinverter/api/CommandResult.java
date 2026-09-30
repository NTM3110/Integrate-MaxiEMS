package io.openems.edge.pvinverter.api;

import io.openems.common.types.OptionsEnum;

public enum CommandResult implements OptionsEnum {
	NONE(0, "None"),
	ACCEPTED(1, "Accepted"),
	REJECTED_DISABLED(2, "Rejected disabled"),
	REJECTED_RANGE(3, "Rejected range"),
	REJECTED_UNSUPPORTED(4, "Rejected unsupported"),
	FAILED(5, "Failed");

	private final int value;
	private final String name;

	private CommandResult(int value, String name) {
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
		return NONE;
	}
}
