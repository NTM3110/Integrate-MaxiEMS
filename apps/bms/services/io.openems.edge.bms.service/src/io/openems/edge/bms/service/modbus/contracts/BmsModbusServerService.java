package io.openems.edge.bms.service.modbus.contracts;

import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.ServerState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;

/** Configures the single managed BMS Modbus server. */
public interface BmsModbusServerService {

	ServerState get() throws Exception;

	ServerState update(boolean enabled, Transport transport, int unitId, String bindAddress, int tcpPort, String serialPortId)
			throws Exception;
}
