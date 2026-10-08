package io.openems.edge.bms.service.time.model;

/** Machine-readable system time synchronization state; unavailable values are null. */
public record BmsTimeStatus(String configuredServer, String serverName, String serverAddress,
		Boolean synchronizedClock, Boolean ntpServiceActive, String timezone, String serviceState, String currentTime,
		Boolean serverReachable, String reachabilityError, Long packetCount, String pollInterval, String error) {
}
