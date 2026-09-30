package io.openems.edge.meter.artel;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.type.TypeUtils;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;

public interface MeterArtelMDM3100 extends ElectricityMeter, OpenemsComponent, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		IMP_ACTIVEPOWER(Doc.of(OpenemsType.LONG)//
				.unit(Unit.KILOWATT_HOURS)//
				.persistencePriority(PersistencePriority.HIGH)),
		EXP_ACTIVEPOWER(Doc.of(OpenemsType.LONG)//
				.unit(Unit.KILOWATT_HOURS)//
				.persistencePriority(PersistencePriority.HIGH)),
		REACTIVE_TOTAL(Doc.of(OpenemsType.LONG)//
				.unit(Unit.KILOVOLT_AMPERE_REACTIVE_HOURS)//
				.persistencePriority(PersistencePriority.HIGH)),
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
