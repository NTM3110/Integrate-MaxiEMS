package io.openems.edge.inverter.hypontech;

import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.Task;

public class HypontechInverterProtocol extends ModbusProtocol {

	private final UnsignedWordElement emsModeRawElement;
	private final SignedWordElement batteryPowerReferenceRawElement;
	private final SignedDoublewordElement activePowerLimitRawElement;
	private final UnsignedWordElement reserveSocRawElement;

	public HypontechInverterProtocol(AbstractOpenemsModbusComponent parent, UnsignedWordElement emsModeRawElement,
			SignedWordElement batteryPowerReferenceRawElement, SignedDoublewordElement activePowerLimitRawElement,
			UnsignedWordElement reserveSocRawElement, Task... tasks) {
		super(parent, tasks);
		this.emsModeRawElement = emsModeRawElement;
		this.batteryPowerReferenceRawElement = batteryPowerReferenceRawElement;
		this.activePowerLimitRawElement = activePowerLimitRawElement;
		this.reserveSocRawElement = reserveSocRawElement;
	}

	UnsignedWordElement emsModeRawElement() {
		return this.emsModeRawElement;
	}

	SignedWordElement batteryPowerReferenceRawElement() {
		return this.batteryPowerReferenceRawElement;
	}

	SignedDoublewordElement activePowerLimitRawElement() {
		return this.activePowerLimitRawElement;
	}

	UnsignedWordElement reserveSocRawElement() {
		return this.reserveSocRawElement;
	}
}
