package io.openems.edge.bms.service.modbus.contracts;

import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.RuntimeState;

/** Runtime status published by the transport-owning controller. */
public interface BmsModbusServerRuntime {

	RuntimeState runtimeState();

	String runtimeError();
}
