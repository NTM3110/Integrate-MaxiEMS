package io.openems.edge.bms.service.tcp.contracts;

import java.util.List;

import io.openems.edge.bms.service.tcp.model.BmsTcpBridgeModels.TcpBridge;

/** Manages the outbound Modbus/TCP bridges exposed to the BMS UI. */
public interface BmsTcpBridgeService {

	List<TcpBridge> list() throws Exception;

	TcpBridge create(String id, String alias, boolean enabled, String ip, int port) throws Exception;

	TcpBridge update(String id, String alias, boolean enabled, String ip, int port) throws Exception;

	void delete(String id) throws Exception;
}
