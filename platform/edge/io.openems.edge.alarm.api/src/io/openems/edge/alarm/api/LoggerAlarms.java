package io.openems.edge.alarm.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.PersistencePriority;
import io.openems.edge.common.channel.Doc;

/** Alarm nature for the logger itself and its downstream communication. */
@ProviderType
public interface LoggerAlarms extends DeviceAlarms {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		LOGGER_INTERNAL_FAULT(AlarmId.LOGGER_INTERNAL_FAULT), //
		LOGGER_STORAGE_FAULT(AlarmId.LOGGER_STORAGE_FAULT), //
		LOGGER_TIME_SYNC_FAULT(AlarmId.LOGGER_TIME_SYNC_FAULT), //
		LOGGER_NETWORK_FAULT(AlarmId.LOGGER_NETWORK_FAULT), //
		DATA_FORWARDING_FAULT(AlarmId.DATA_FORWARDING_FAULT), //
		DOWNSTREAM_COMMUNICATION_FAULT(AlarmId.DOWNSTREAM_COMMUNICATION_FAULT);

		private final Doc doc;

		private ChannelId(AlarmId alarmId) {
			this.doc = Doc.of(alarmId.level()) //
					.text(alarmId.text()) //
					.persistencePriority(PersistencePriority.HIGH);
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	@Override
	public default long getAllowedAlarmMask() {
		return AlarmId.DEVICE_MASK | AlarmId.LOGGER_MASK;
	}
}
