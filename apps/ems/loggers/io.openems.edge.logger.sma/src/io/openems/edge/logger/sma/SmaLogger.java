package io.openems.edge.logger.sma;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.meter.api.ElectricityMeter;

public interface SmaLogger extends SmartLogger, ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		GRID_CONNECTION_POINT_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH));

		private final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the SMA Daily yield (register 30517), in kWh.
	 *
	 * @return the EnergyProductionToday channel
	 */
	default Channel<Float> getDailyYieldChannel() {
		return this.getEnergyProductionTodayChannel();
	}

	/**
	 * Gets the SMA Power (register 30775), in W.
	 *
	 * @return the ActivePower channel
	 */
	default IntegerReadChannel getPowerChannel() {
		return this.getActivePowerChannel();
	}

	/**
	 * Gets the grid connection point active power (register 31249), in W.
	 *
	 * @return the grid connection point active power channel
	 */
	default IntegerReadChannel getGridConnectionPointActivePowerChannel() {
		return this.channel(ChannelId.GRID_CONNECTION_POINT_ACTIVE_POWER);
	}
}
