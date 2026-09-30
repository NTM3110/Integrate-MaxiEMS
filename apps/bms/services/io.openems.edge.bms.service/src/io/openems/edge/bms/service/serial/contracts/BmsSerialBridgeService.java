package io.openems.edge.bms.service.serial.contracts;

import java.util.List;

import io.openems.edge.bms.service.serial.model.BmsSerialBridgeModels.SerialBridge;

/** Lists and updates the serial bridges exposed to the BMS UI. */
public interface BmsSerialBridgeService {

	List<SerialBridge> list() throws Exception;

	SerialBridge update(String id, String alias, int baudRate, int databits, String parity, String stopbits)
			throws Exception;
}
