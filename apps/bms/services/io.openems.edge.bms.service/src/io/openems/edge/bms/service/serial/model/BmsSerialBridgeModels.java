package io.openems.edge.bms.service.serial.model;

/** Wire model for an OpenEMS serial Modbus bridge configuration. */
public final class BmsSerialBridgeModels {

	private BmsSerialBridgeModels() {
	}

	public record SerialBridge(String id, String alias, boolean enabled, String portName, int baudRate, int databits,
			String parity, String stopbits, boolean batchAcquisition, int responseTimeoutMs) {
	}
}
