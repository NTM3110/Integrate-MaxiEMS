package io.openems.edge.bms.service.network.model;

/** Data exchanged between the BMS network service and its JSON API. */
public final class BmsNetworkModels {

	private BmsNetworkModels() {
	}

	public record NetworkPort(String id, String name, String interfaceName, boolean available, boolean connected,
			String status, String error, String ipAddress, String subnetMask, String gateway, String dns, String mode) {
	}

	public record NetworkUpdate(String mode, String ipAddress, String subnetMask, String gateway, String dns) {
	}
}
