package io.openems.edge.inverter.hypontech;

import io.openems.common.types.OptionsEnum;

public enum HypontechBatteryState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"),
	SLEEP(0, "Sleep"),
	CHARGING(1, "Charging"),
	DISCHARGING(2, "Discharging"),
	IDLE(3, "Idle"),
	STANDBY(4, "Standby"),
	RUN(5, "Run"),
	FAULT(6, "Fault"),
	OFFLINE(7, "Offline");

	private final int value;
	private final String name;

	private HypontechBatteryState(int value, String name) {
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
		case 0 -> SLEEP.value;
		case 1 -> CHARGING.value;
		case 2 -> DISCHARGING.value;
		case 3 -> IDLE.value;
		case 4 -> STANDBY.value;
		case 5 -> RUN.value;
		case 6 -> FAULT.value;
		case 7 -> OFFLINE.value;
		default -> null;
		};
	}
}
