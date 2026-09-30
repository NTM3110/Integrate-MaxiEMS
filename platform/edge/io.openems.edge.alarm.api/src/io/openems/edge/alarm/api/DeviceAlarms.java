package io.openems.edge.alarm.api;

import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/** Common alarm nature shared by physical Edge devices. */
@ProviderType
public interface DeviceAlarms extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		SUPPORTED_ALARM_MASK(Doc.of(OpenemsType.LONG) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		ALARM_DATA_VALID(Doc.of(OpenemsType.BOOLEAN) //
				.persistencePriority(PersistencePriority.HIGH)), //
		COMMUNICATION_LOST(AlarmId.COMMUNICATION_LOST), //
		DEVICE_OFFLINE(AlarmId.DEVICE_OFFLINE), //
		INVALID_DATA(AlarmId.INVALID_DATA), //
		CONFIGURATION_ERROR(AlarmId.CONFIGURATION_ERROR), //
		UNSUPPORTED_DEVICE(AlarmId.UNSUPPORTED_DEVICE);

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		private ChannelId(AlarmId alarmId) {
			this(Doc.of(alarmId.level()) //
					.text(alarmId.text()) //
					.persistencePriority(PersistencePriority.HIGH));
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public default long getAllowedAlarmMask() {
		return AlarmId.DEVICE_MASK;
	}

	public default LongReadChannel getSupportedAlarmMaskChannel() {
		return this.channel(ChannelId.SUPPORTED_ALARM_MASK);
	}

	public default Value<Long> getSupportedAlarmMask() {
		return this.getSupportedAlarmMaskChannel().value();
	}

	public default void _setSupportedAlarmMask(Long value) {
		this.getSupportedAlarmMaskChannel().setNextValue(value);
	}

	public default BooleanReadChannel getAlarmDataValidChannel() {
		return this.channel(ChannelId.ALARM_DATA_VALID);
	}

	public default Value<Boolean> getAlarmDataValid() {
		return this.getAlarmDataValidChannel().value();
	}

	public default void _setAlarmDataValid(Boolean value) {
		this.getAlarmDataValidChannel().setNextValue(value);
	}

	public default StateChannel getAlarmChannel(AlarmId alarmId) {
		return switch (alarmId.profile()) {
		case DEVICE -> this.channel(ChannelId.valueOf(alarmId.name()));
		case INVERTER -> {
			if (!(this instanceof InverterAlarms)) {
				throw unsupportedProfile(alarmId);
			}
			yield this.channel(InverterAlarms.ChannelId.valueOf(alarmId.name()));
		}
		case LOGGER -> {
			if (!(this instanceof LoggerAlarms)) {
				throw unsupportedProfile(alarmId);
			}
			yield this.channel(LoggerAlarms.ChannelId.valueOf(alarmId.name()));
		}
		};
	}

	public default Value<Boolean> getAlarm(AlarmId alarmId) {
		return this.getAlarmChannel(alarmId).value();
	}

	public default void _setAlarm(AlarmId alarmId, Boolean value) {
		this.getAlarmChannel(alarmId).setNextValue(value);
	}

	/** Initializes capability metadata and all available alarm channels as unknown. */
	public default void initializeAlarms(long supportedAlarmMask) {
		this.validateSupportedAlarmMask(supportedAlarmMask);
		this._setSupportedAlarmMask(supportedAlarmMask);
		this._setAlarmDataValid(false);
		for (var alarmId : AlarmId.values()) {
			if (AlarmId.isSupported(this.getAllowedAlarmMask(), alarmId)) {
				this._setAlarm(alarmId, null);
			}
		}
	}

	/** Invalidates device-derived alarm values without clearing local diagnostics. */
	public default void invalidateAlarmData() {
		this._setAlarmDataValid(false);
		for (var alarmId : AlarmId.values()) {
			if (alarmId.isDeviceDerived() && AlarmId.isSupported(this.getAllowedAlarmMask(), alarmId)) {
				this._setAlarm(alarmId, null);
			}
		}
	}

	/** Applies one complete alarm snapshot for the declared capability mask. */
	public default void applyAlarmSnapshot(long supportedAlarmMask, Map<AlarmId, Boolean> snapshot) {
		this.validateSupportedAlarmMask(supportedAlarmMask);
		for (var alarmId : AlarmId.values()) {
			if (alarmId == AlarmId.COMMUNICATION_LOST) {
				continue;
			}
			if (AlarmId.isSupported(supportedAlarmMask, alarmId)
					&& (!snapshot.containsKey(alarmId) || snapshot.get(alarmId) == null)) {
				throw new IllegalArgumentException("Missing valid snapshot value for " + alarmId.name());
			}
		}

		this._setSupportedAlarmMask(supportedAlarmMask);
		for (var alarmId : AlarmId.values()) {
			if (alarmId != AlarmId.COMMUNICATION_LOST
					&& AlarmId.isSupported(this.getAllowedAlarmMask(), alarmId)) {
				this._setAlarm(alarmId,
						AlarmId.isSupported(supportedAlarmMask, alarmId) ? snapshot.get(alarmId) : null);
			}
		}
		this._setAlarmDataValid(true);
	}

	private void validateSupportedAlarmMask(long supportedAlarmMask) {
		if ((supportedAlarmMask & ~this.getAllowedAlarmMask()) != 0) {
			throw new IllegalArgumentException("Supported alarm mask contains alarms outside component profile");
		}
	}

	private static IllegalArgumentException unsupportedProfile(AlarmId alarmId) {
		return new IllegalArgumentException("Alarm " + alarmId.name() + " is not available on this component profile");
	}
}
