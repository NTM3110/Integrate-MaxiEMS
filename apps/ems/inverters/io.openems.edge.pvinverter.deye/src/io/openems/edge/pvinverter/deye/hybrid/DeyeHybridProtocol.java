package io.openems.edge.pvinverter.deye.hybrid;

import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.Task;

public class DeyeHybridProtocol extends ModbusProtocol {

	private final UnsignedWordElement activePowerLimitRawElement;
	private final UnsignedWordElement chargeCurrentLimitRawElement;
	private final UnsignedWordElement dischargeCurrentLimitRawElement;
	private final UnsignedWordElement reserveSocRawElement;
	private final UnsignedWordElement gridChargeEnableRawElement;
	private final UnsignedWordElement emsModeRawElement;
	private final UnsignedWordElement remoteEnableRawElement;
	private final UnsignedWordElement heartbeatRawElement;
	private final SignedWordElement batteryPowerReferenceRawElement;

	public DeyeHybridProtocol(AbstractOpenemsModbusComponent parent, //
			UnsignedWordElement activePowerLimitRawElement, //
			UnsignedWordElement chargeCurrentLimitRawElement, //
			UnsignedWordElement dischargeCurrentLimitRawElement, //
			UnsignedWordElement reserveSocRawElement, //
			UnsignedWordElement gridChargeEnableRawElement, //
			UnsignedWordElement emsModeRawElement, //
			UnsignedWordElement remoteEnableRawElement, //
			UnsignedWordElement heartbeatRawElement, //
			SignedWordElement batteryPowerReferenceRawElement, //
			Task... tasks) {
		super(parent, tasks);
		this.activePowerLimitRawElement = activePowerLimitRawElement;
		this.chargeCurrentLimitRawElement = chargeCurrentLimitRawElement;
		this.dischargeCurrentLimitRawElement = dischargeCurrentLimitRawElement;
		this.reserveSocRawElement = reserveSocRawElement;
		this.gridChargeEnableRawElement = gridChargeEnableRawElement;
		this.emsModeRawElement = emsModeRawElement;
		this.remoteEnableRawElement = remoteEnableRawElement;
		this.heartbeatRawElement = heartbeatRawElement;
		this.batteryPowerReferenceRawElement = batteryPowerReferenceRawElement;
	}

	UnsignedWordElement activePowerLimitRawElement() {
		return this.activePowerLimitRawElement;
	}

	UnsignedWordElement chargeCurrentLimitRawElement() {
		return this.chargeCurrentLimitRawElement;
	}

	UnsignedWordElement dischargeCurrentLimitRawElement() {
		return this.dischargeCurrentLimitRawElement;
	}

	UnsignedWordElement reserveSocRawElement() {
		return this.reserveSocRawElement;
	}

	UnsignedWordElement gridChargeEnableRawElement() {
		return this.gridChargeEnableRawElement;
	}

	UnsignedWordElement emsModeRawElement() {
		return this.emsModeRawElement;
	}

	UnsignedWordElement remoteEnableRawElement() {
		return this.remoteEnableRawElement;
	}

	UnsignedWordElement heartbeatRawElement() {
		return this.heartbeatRawElement;
	}

	SignedWordElement batteryPowerReferenceRawElement() {
		return this.batteryPowerReferenceRawElement;
	}
}
