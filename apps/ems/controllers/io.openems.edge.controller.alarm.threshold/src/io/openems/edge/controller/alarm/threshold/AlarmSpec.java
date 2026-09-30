package io.openems.edge.controller.alarm.threshold;

import io.openems.edge.wika.api.WikaAms01Alarm;

record AlarmSpec(AlarmType type, Compartment compartment, WikaAms01Alarm.ChannelId flagChannel,
		WikaAms01Alarm.ChannelId valueChannel, String valueUnit) {
	static final AlarmSpec[] VALUES = {
			new AlarmSpec(AlarmType.TEMPERATURE, Compartment.IN, WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_FLAG,
					WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_VALUE, " °C"),
			new AlarmSpec(AlarmType.TEMPERATURE, Compartment.OUT, WikaAms01Alarm.ChannelId.OUT_ALARM_TEMP_FLAG,
					WikaAms01Alarm.ChannelId.OUT_ALARM_TEMP_VALUE, " °C"),
			new AlarmSpec(AlarmType.TEMPERATURE, Compartment.MBA, WikaAms01Alarm.ChannelId.MBA_ALARM_TEMP_FLAG,
					WikaAms01Alarm.ChannelId.MBA_ALARM_TEMP_VALUE, " °C"),
			new AlarmSpec(AlarmType.PD, Compartment.IN, WikaAms01Alarm.ChannelId.IN_ALARM_PD_FLAG,
					null, ""),
			new AlarmSpec(AlarmType.PD, Compartment.OUT, WikaAms01Alarm.ChannelId.OUT_ALARM_PD_FLAG,
					null, ""),
			new AlarmSpec(AlarmType.PD, Compartment.MBA, WikaAms01Alarm.ChannelId.MBA_ALARM_PD_FLAG,
					null, "") };
}
