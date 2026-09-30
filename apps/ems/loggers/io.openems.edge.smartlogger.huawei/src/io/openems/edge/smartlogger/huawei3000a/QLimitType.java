package io.openems.edge.smartlogger.huawei3000a;

import io.openems.common.types.OptionsEnum;

public enum QLimitType implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NONE(0, "None"), //
	PERCENTAGE(1, "Percentage"), //
	FIXED_LIMIT(2, "Fixed Limit"), //
	POWER_FACTOR(3, "Power Factor");

	private final int value;
	private final String name;

	private QLimitType(int value, String name) {
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
