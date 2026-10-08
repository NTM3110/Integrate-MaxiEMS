package io.openems.edge.bms.service.time.contracts;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bms.service.time.model.BmsTimeStatus;

public interface BmsTimeService {
	BmsTimeStatus status() throws OpenemsException;
	BmsTimeStatus configure(String serverIp) throws OpenemsException;
}
