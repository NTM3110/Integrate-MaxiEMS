package io.openems.edge.smartlogger.meter.huawei;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

import io.openems.edge.meter.api.ElectricityMeter;

public interface MeterHuawei extends ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		AB_LINE_VOLTAGE(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIVOLT)), //
		BC_LINE_VOLTAGE(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIVOLT)), //
		CA_LINE_VOLTAGE(Doc.of(OpenemsType.INTEGER).unit(Unit.MILLIVOLT)), //
		ACTIVE_ELECTRICITY(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT_HOURS)), //
		REACTIVE_ELECTRICITY(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE_REACTIVE_HOURS)), //
		POWER_FACTOR(Doc.of(OpenemsType.FLOAT).unit(Unit.NONE)), //
		POSITIVE_ACTIVE_ELECTRICITY(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT_HOURS));

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
