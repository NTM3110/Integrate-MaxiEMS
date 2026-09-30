package io.openems.edge.inverter.ligoo;

import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.Task;

public class LigooInverterProtocol extends ModbusProtocol {

	private final UnsignedWordElement emsModeRawElement;
	private final UnsignedWordElement chargeCurrentLimitRawElement;
	private final UnsignedWordElement dischargeCurrentLimitRawElement;
	private final UnsignedWordElement systemCommandRawElement;
	private final UnsignedWordElement reserveSocRawElement;
	private final UnsignedWordElement gridChargeEnableRawElement;
	private final UnsignedWordElement activePowerLimitRawElement;

	public LigooInverterProtocol(AbstractOpenemsModbusComponent parent, UnsignedWordElement emsModeRawElement,
			UnsignedWordElement chargeCurrentLimitRawElement, UnsignedWordElement dischargeCurrentLimitRawElement,
			UnsignedWordElement systemCommandRawElement, UnsignedWordElement reserveSocRawElement,
			UnsignedWordElement gridChargeEnableRawElement, UnsignedWordElement activePowerLimitRawElement, Task... tasks) {
		super(parent, tasks);
		this.emsModeRawElement = emsModeRawElement;
		this.chargeCurrentLimitRawElement = chargeCurrentLimitRawElement;
		this.dischargeCurrentLimitRawElement = dischargeCurrentLimitRawElement;
		this.systemCommandRawElement = systemCommandRawElement;
		this.reserveSocRawElement = reserveSocRawElement;
		this.gridChargeEnableRawElement = gridChargeEnableRawElement;
		this.activePowerLimitRawElement = activePowerLimitRawElement;
	}

	UnsignedWordElement emsModeRawElement() {
		return this.emsModeRawElement;
	}

	UnsignedWordElement chargeCurrentLimitRawElement() {
		return this.chargeCurrentLimitRawElement;
	}

	UnsignedWordElement dischargeCurrentLimitRawElement() {
		return this.dischargeCurrentLimitRawElement;
	}

	UnsignedWordElement systemCommandRawElement() {
		return this.systemCommandRawElement;
	}

	UnsignedWordElement reserveSocRawElement() {
		return this.reserveSocRawElement;
	}

	UnsignedWordElement gridChargeEnableRawElement() {
		return this.gridChargeEnableRawElement;
	}

	UnsignedWordElement activePowerLimitRawElement() {
		return this.activePowerLimitRawElement;
	}
}
