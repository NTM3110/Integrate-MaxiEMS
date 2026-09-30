package io.openems.edge.inverter.hypontech;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.PersistencePriority;
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

public interface HypontechInverter extends RtsPvInverter, ElectricityMeter, HybridInverter, HybridInverterControl,
		HybridInverterAlarms, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent, EventHandler {

	@Override
	public default void setReactivePowerLimit(Integer value) throws OpenemsNamedException {
		throw new OpenemsException("Reactive power control is not supported by HYPONTECH");
	}

	@Override
	public default void setReactivePowerLimit(int value) throws OpenemsNamedException {
		this.setReactivePowerLimit(Integer.valueOf(value));
	}

	@Override
	public default void setReactivePowerLimitPercent(Integer value) throws OpenemsNamedException {
		throw new OpenemsException("Reactive power control is not supported by HYPONTECH");
	}

	@Override
	public default void setReactivePowerLimitPercent(int value) throws OpenemsNamedException {
		this.setReactivePowerLimitPercent(Integer.valueOf(value));
	}

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		MANUFACTURER(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.MEDIUM)),
		MODEL(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.MEDIUM)),
		SERIAL_NUMBER(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.MEDIUM)),
		SOFTWARE_VERSION(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.MEDIUM));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
