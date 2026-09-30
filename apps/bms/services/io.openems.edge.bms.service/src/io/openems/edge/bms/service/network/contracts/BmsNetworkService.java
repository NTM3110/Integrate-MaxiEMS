package io.openems.edge.bms.service.network.contracts;

import io.openems.edge.bms.service.network.model.BmsNetworkModels;

import java.util.List;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bms.service.network.model.BmsNetworkModels.NetworkPort;
import io.openems.edge.bms.service.network.model.BmsNetworkModels.NetworkUpdate;

/** Reads and applies the BMS appliance's explicitly configured WAN interfaces. */
public interface BmsNetworkService {

	public List<NetworkPort> list() throws OpenemsException;

	public NetworkPort update(String id, NetworkUpdate update) throws OpenemsException;
}
