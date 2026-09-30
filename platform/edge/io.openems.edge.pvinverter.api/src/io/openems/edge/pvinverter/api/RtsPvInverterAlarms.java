package io.openems.edge.pvinverter.api;

import java.util.Map;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

/** Common alarm nature for RTS PV inverters. */
public interface RtsPvInverterAlarms extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		SUPPORTED_ALARM_MASK(Doc.of(OpenemsType.LONG) //
				.persistencePriority(PersistencePriority.MEDIUM)), //
		ALARM_DATA_VALID(Doc.of(OpenemsType.BOOLEAN) //
				.persistencePriority(PersistencePriority.HIGH)), //
		DEVICE_OFFLINE(RtsPvInverterAlarmId.DEVICE_OFFLINE), //
		INVALID_DATA(RtsPvInverterAlarmId.INVALID_DATA), //
		CONFIGURATION_ERROR(RtsPvInverterAlarmId.CONFIGURATION_ERROR), //
		UNSUPPORTED_DEVICE(RtsPvInverterAlarmId.UNSUPPORTED_DEVICE), //
		GRID_OVER_VOLTAGE(RtsPvInverterAlarmId.GRID_OVER_VOLTAGE), //
		GRID_UNDER_VOLTAGE(RtsPvInverterAlarmId.GRID_UNDER_VOLTAGE), //
		GRID_OVER_FREQUENCY(RtsPvInverterAlarmId.GRID_OVER_FREQUENCY), //
		GRID_UNDER_FREQUENCY(RtsPvInverterAlarmId.GRID_UNDER_FREQUENCY), //
		GRID_VOLTAGE_IMBALANCE(RtsPvInverterAlarmId.GRID_VOLTAGE_IMBALANCE), //
		GRID_FREQUENCY_ABNORMAL(RtsPvInverterAlarmId.GRID_FREQUENCY_ABNORMAL), //
		GRID_PHASE_LOSS(RtsPvInverterAlarmId.GRID_PHASE_LOSS), //
		GRID_PHASE_SEQUENCE_ERROR(RtsPvInverterAlarmId.GRID_PHASE_SEQUENCE_ERROR), //
		GRID_CONNECTION_FAILURE(RtsPvInverterAlarmId.GRID_CONNECTION_FAILURE), //
		GRID_RECONNECT_FAILURE(RtsPvInverterAlarmId.GRID_RECONNECT_FAILURE), //
		REVERSE_POWER_DETECTED(RtsPvInverterAlarmId.REVERSE_POWER_DETECTED), //
		WEAK_GRID_DETECTED(RtsPvInverterAlarmId.WEAK_GRID_DETECTED), //
		GRID_FAULT(RtsPvInverterAlarmId.GRID_FAULT), //
		DC_OVER_VOLTAGE(RtsPvInverterAlarmId.DC_OVER_VOLTAGE), //
		DC_UNDER_VOLTAGE(RtsPvInverterAlarmId.DC_UNDER_VOLTAGE), //
		DC_OVER_CURRENT(RtsPvInverterAlarmId.DC_OVER_CURRENT), //
		DC_REVERSE_POLARITY(RtsPvInverterAlarmId.DC_REVERSE_POLARITY), //
		PV_ISOLATION_FAULT(RtsPvInverterAlarmId.PV_ISOLATION_FAULT), //
		GROUND_FAULT(RtsPvInverterAlarmId.GROUND_FAULT), //
		ARC_FAULT(RtsPvInverterAlarmId.ARC_FAULT), //
		LEAKAGE_CURRENT_HIGH(RtsPvInverterAlarmId.LEAKAGE_CURRENT_HIGH), //
		INSULATION_RESISTANCE_LOW(RtsPvInverterAlarmId.INSULATION_RESISTANCE_LOW), //
		PV_STRING_FAULT(RtsPvInverterAlarmId.PV_STRING_FAULT), //
		PV_INPUT_FAILURE(RtsPvInverterAlarmId.PV_INPUT_FAILURE), //
		DC_FAULT(RtsPvInverterAlarmId.DC_FAULT);

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		private ChannelId(RtsPvInverterAlarmId alarmId) {
			this(Doc.of(alarmId.level()) //
					.text(alarmId.text()) //
					.persistencePriority(PersistencePriority.HIGH));
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
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

	public default StateChannel getAlarmChannel(RtsPvInverterAlarmId alarmId) {
		var channelId = alarmId.channelId().orElseThrow(() -> new IllegalArgumentException(
				"Communication Lost reuses the transport communication-failed channel"));
		return this.channel(channelId);
	}

	public default Value<Boolean> getAlarm(RtsPvInverterAlarmId alarmId) {
		return this.getAlarmChannel(alarmId).value();
	}

	public default void _setAlarm(RtsPvInverterAlarmId alarmId, Boolean value) {
		this.getAlarmChannel(alarmId).setNextValue(value);
	}

	/** Initializes capability metadata and all device alarms as unknown. */
	public default void initializeRtsPvInverterAlarms(long supportedAlarmMask) {
		validateSupportedAlarmMask(supportedAlarmMask);
		this._setSupportedAlarmMask(supportedAlarmMask);
		this._setAlarmDataValid(false);
		for (var alarmId : RtsPvInverterAlarmId.values()) {
			alarmId.channelId().ifPresent(ignore -> this._setAlarm(alarmId, null));
		}
	}

	/** Invalidates device-derived alarms without reporting a false clear. */
	public default void invalidateRtsPvInverterAlarmData() {
		this._setAlarmDataValid(false);
		for (var alarmId : RtsPvInverterAlarmId.values()) {
			if (alarmId.isDeviceDerived()) {
				this._setAlarm(alarmId, null);
			}
		}
	}

	/**
	 * Applies one complete snapshot. Every supported device alarm must be present.
	 * Unsupported alarms are set to unknown and must not be interpreted as safe.
	 *
	 * @param supportedAlarmMask model capability mask
	 * @param snapshot           complete values for supported device alarms
	 */
	public default void applyRtsPvInverterAlarmSnapshot(long supportedAlarmMask,
			Map<RtsPvInverterAlarmId, Boolean> snapshot) {
		validateSupportedAlarmMask(supportedAlarmMask);
		for (var alarmId : RtsPvInverterAlarmId.values()) {
			if (alarmId == RtsPvInverterAlarmId.COMMUNICATION_LOST) {
				continue;
			}
			if (RtsPvInverterAlarmId.isSupported(supportedAlarmMask, alarmId)
					&& (!snapshot.containsKey(alarmId) || snapshot.get(alarmId) == null)) {
				throw new IllegalArgumentException("Missing valid snapshot value for " + alarmId.name());
			}
		}

		this._setSupportedAlarmMask(supportedAlarmMask);
		for (var alarmId : RtsPvInverterAlarmId.values()) {
			alarmId.channelId().ifPresent(ignore -> this._setAlarm(alarmId,
					RtsPvInverterAlarmId.isSupported(supportedAlarmMask, alarmId) ? snapshot.get(alarmId) : null));
		}
		this._setAlarmDataValid(true);
	}

	private static void validateSupportedAlarmMask(long supportedAlarmMask) {
		if ((supportedAlarmMask & ~RtsPvInverterAlarmId.ALL_MASK) != 0) {
			throw new IllegalArgumentException("Supported alarm mask contains undefined bits");
		}
	}
}
