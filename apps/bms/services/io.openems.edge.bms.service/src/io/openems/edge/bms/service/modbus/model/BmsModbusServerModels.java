package io.openems.edge.bms.service.modbus.model;

import java.util.List;

/** Authoritative configuration and runtime models for the BMS Modbus server. */
public final class BmsModbusServerModels {

	private BmsModbusServerModels() {
	}

	public enum Transport {
		TCP, SERIAL_RTU
	}

	public enum RuntimeState {
		DISABLED, STARTING, ACTIVE, ERROR
	}

	public record SerialPort(String id, String displayName, String portName, String serialFormat,
			boolean available, boolean selected, String owner, List<String> ownerIds) {
	}

	public record ServerState(boolean enabled, Transport transport, int unitId, String bindAddress, int tcpPort, String serialPortId,
			RuntimeState runtimeState, String runtimeError, List<SerialPort> ports) {
	}
}
