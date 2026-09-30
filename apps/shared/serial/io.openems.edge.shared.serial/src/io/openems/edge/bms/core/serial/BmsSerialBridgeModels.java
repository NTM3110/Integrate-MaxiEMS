package io.openems.edge.bms.core.serial;

/** Wire model for a native OpenEMS Bridge.Modbus.Serial configuration. */
public final class BmsSerialBridgeModels {

	private BmsSerialBridgeModels() {
	}

	public record SerialBridge(String id, String alias, boolean enabled, String portName, int baudRate, int databits,
			String parity, String stopbits) {
	}
}
