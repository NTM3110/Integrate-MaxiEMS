package io.openems.edge.bms.service.tcp.model;

/** Wire models for native OpenEMS {@code Bridge.Modbus.Tcp} configurations. */
public final class BmsTcpBridgeModels {

	private BmsTcpBridgeModels() {
	}

	public record TcpBridge(String id, String alias, boolean enabled, String ip, int port) {
	}
}
