package io.openems.edge.network.manager.os;

import java.util.List;

public record ObservedNetworkState(String deviceState, boolean carrier, List<String> addresses, String gateway,
		List<String> dns, String activeProfile) {

	public ObservedNetworkState {
		addresses = List.copyOf(addresses);
		dns = List.copyOf(dns);
	}
}
