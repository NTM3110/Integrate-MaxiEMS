package io.openems.edge.bms.service.maintenance.contracts;

import java.util.List;

import io.openems.common.exceptions.OpenemsException;

/** Exclusive, temporary diagnostic access to a configured BMS Modbus bridge. */
public interface BmsMaintenanceService {
	public record TaskStatus(String type, String state, Integer stringIndex, String bridgeId, String serialPort,
			Integer startId, Integer endId, boolean running, Integer currentId, Integer nextId,
			List<Integer> activeIds, List<Integer> missingIds,
			List<Integer> activatedIds, List<Integer> notActivatedIds, Integer transformerRange,
			Integer calibrationSigned, Integer calibrationRaw, Integer firstCurrentSignedRaw, Integer latestCurrentRaw,
			Integer latestCurrentSignedRaw, Double latestCurrentAmps, Integer currentSampleIndex,
			Integer successfulCurrentSamples, Boolean currentChanged, String error, List<String> output) {}

	TaskStatus status();
	TaskStatus scanIr(int stringIndex, int startId, int endId) throws OpenemsException;
	TaskStatus activateIr(int stringIndex, int startId, int endId) throws OpenemsException;
	TaskStatus tcTask(int stringIndex, String action, Integer value) throws OpenemsException;
	TaskStatus stop() throws OpenemsException;
	String wifi(String action, String ssid, String bssid, String password) throws OpenemsException;
	String gpio(String action, String settingsJson) throws OpenemsException;
}
