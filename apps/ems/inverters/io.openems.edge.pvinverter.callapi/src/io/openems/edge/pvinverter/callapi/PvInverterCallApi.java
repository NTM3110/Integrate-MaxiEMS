package io.openems.edge.pvinverter.callapi;

import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.SinglePhaseMeter;

public interface PvInverterCallApi extends SinglePhaseMeter, ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)), //
		API_COMMUNICATION_FAILED(Doc.of(Level.WARNING) //
				.text("API communication failed")
				.persistencePriority(PersistencePriority.HIGH)), //
		REACTIVE_PRODUCTION_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE_HOURS)
				.persistencePriority(PersistencePriority.HIGH)), //
		REACTIVE_CONSUMPTION_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE_HOURS)
				.persistencePriority(PersistencePriority.HIGH)), //
		;

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
