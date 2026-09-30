package io.openems.edge.inverter.ligoo;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;

public interface LigooInverter extends RtsPvInverter, ElectricityMeter, HybridInverter, HybridInverterControl,
		HybridInverterAlarms, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent, EventHandler {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		DEVICE_SYSTEM_TYPE(text()),
		SERIAL_NUMBER(text()),
		FIRMWARE_VERSION(text()),
		HARDWARE_VERSION(text()),
		PROTOCOL_VERSION(text()),
		RATED_POWER(integer(Unit.WATT)),
		ALARM_ACTIVE_COUNT(integer(Unit.NONE));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	private static Doc text() {
		return Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.MEDIUM);
	}

	private static Doc integer(Unit unit) {
		return Doc.of(OpenemsType.INTEGER).unit(unit).persistencePriority(PersistencePriority.HIGH);
	}

	private static OpenemsException unsupportedReactiveControl() {
		return new OpenemsException("LIGOO does not support reactive power limit writes");
	}

	@Override
	public default void setModesQ(Integer value) throws OpenemsNamedException {
		throw unsupportedReactiveControl();
	}

	@Override
	public default void setModesQ(int value) throws OpenemsNamedException {
		throw unsupportedReactiveControl();
	}

	@Override
	public default void setReactivePowerLimit(Integer value) throws OpenemsNamedException {
		throw unsupportedReactiveControl();
	}

	@Override
	public default void setReactivePowerLimit(int value) throws OpenemsNamedException {
		throw unsupportedReactiveControl();
	}

	@Override
	public default void setReactivePowerLimitPercent(Integer value) throws OpenemsNamedException {
		throw unsupportedReactiveControl();
	}

	@Override
	public default void setReactivePowerLimitPercent(int value) throws OpenemsNamedException {
		throw unsupportedReactiveControl();
	}
}
