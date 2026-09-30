package io.openems.edge.controller.alarm.threshold;

import java.time.Duration;
import java.time.ZonedDateTime;

record AlarmTransition(String componentId, String station, AlarmType type, Compartment compartment, int oldLevel,
		int newLevel, ZonedDateTime timestamp, String deviationValue, ZonedDateTime alarmStartedAt,
		Duration alarmDuration) {
}
