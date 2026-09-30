package io.openems.edge.network.manager.os;

import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;

public interface NetworkSystemAdapter {
	NetworkProfile ensureProfile(String interfaceName) throws Exception;

	String createCheckpoint(String interfaceName, int timeoutSeconds) throws Exception;

	void apply(NetworkProfile profile, NetworkInterfaceConfig config) throws Exception;

	void activate(NetworkProfile profile, String interfaceName) throws Exception;

	NetworkVerification verify(NetworkProfile profile, NetworkInterfaceConfig config) throws Exception;

	void commitCheckpoint(String checkpointId) throws Exception;

	void rollbackCheckpoint(String checkpointId) throws Exception;

	ObservedNetworkState observe(String interfaceName) throws Exception;
}
