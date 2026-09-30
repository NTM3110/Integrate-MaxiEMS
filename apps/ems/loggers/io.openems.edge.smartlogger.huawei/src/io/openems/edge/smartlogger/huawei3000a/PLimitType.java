package io.openems.edge.smartlogger.huawei3000a;

import io.openems.common.types.OptionsEnum;

public enum PLimitType implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	FIXED_LIMIT(0, "Fixed Limit"), //
	MAX_PRODUCTIVE_POWER(1, "Max Productive Power");

	private final int value;
	private final String name;

	private PLimitType(int value, String name) {
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
