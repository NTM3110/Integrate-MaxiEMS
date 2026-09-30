package io.openems.edge.alarm.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.PersistencePriority;
import io.openems.edge.common.channel.Doc;

/** Grid and DC/PV alarm nature for inverter components. */
@ProviderType
public interface InverterAlarms extends DeviceAlarms {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		GRID_OVER_VOLTAGE(AlarmId.GRID_OVER_VOLTAGE), //
		GRID_UNDER_VOLTAGE(AlarmId.GRID_UNDER_VOLTAGE), //
		GRID_OVER_FREQUENCY(AlarmId.GRID_OVER_FREQUENCY), //
		GRID_UNDER_FREQUENCY(AlarmId.GRID_UNDER_FREQUENCY), //
		GRID_VOLTAGE_IMBALANCE(AlarmId.GRID_VOLTAGE_IMBALANCE), //
		GRID_FREQUENCY_ABNORMAL(AlarmId.GRID_FREQUENCY_ABNORMAL), //
		GRID_PHASE_LOSS(AlarmId.GRID_PHASE_LOSS), //
		GRID_PHASE_SEQUENCE_ERROR(AlarmId.GRID_PHASE_SEQUENCE_ERROR), //
		GRID_CONNECTION_FAILURE(AlarmId.GRID_CONNECTION_FAILURE), //
		GRID_RECONNECT_FAILURE(AlarmId.GRID_RECONNECT_FAILURE), //
		REVERSE_POWER_DETECTED(AlarmId.REVERSE_POWER_DETECTED), //
		WEAK_GRID_DETECTED(AlarmId.WEAK_GRID_DETECTED), //
		GRID_FAULT(AlarmId.GRID_FAULT), //
		DC_OVER_VOLTAGE(AlarmId.DC_OVER_VOLTAGE), //
		DC_UNDER_VOLTAGE(AlarmId.DC_UNDER_VOLTAGE), //
		DC_OVER_CURRENT(AlarmId.DC_OVER_CURRENT), //
		DC_REVERSE_POLARITY(AlarmId.DC_REVERSE_POLARITY), //
		PV_ISOLATION_FAULT(AlarmId.PV_ISOLATION_FAULT), //
		GROUND_FAULT(AlarmId.GROUND_FAULT), //
		ARC_FAULT(AlarmId.ARC_FAULT), //
		LEAKAGE_CURRENT_HIGH(AlarmId.LEAKAGE_CURRENT_HIGH), //
		INSULATION_RESISTANCE_LOW(AlarmId.INSULATION_RESISTANCE_LOW), //
		PV_STRING_FAULT(AlarmId.PV_STRING_FAULT), //
		PV_INPUT_FAILURE(AlarmId.PV_INPUT_FAILURE), //
		DC_FAULT(AlarmId.DC_FAULT);

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
		return AlarmId.DEVICE_MASK | AlarmId.INVERTER_MASK;
	}
}
