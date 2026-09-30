package io.openems.edge.network.manager.os;

import java.util.Objects;

public record NetworkProfile(String uuid, String interfaceName) {
	public NetworkProfile {
		Objects.requireNonNull(uuid);
		Objects.requireNonNull(interfaceName);
	}
}
