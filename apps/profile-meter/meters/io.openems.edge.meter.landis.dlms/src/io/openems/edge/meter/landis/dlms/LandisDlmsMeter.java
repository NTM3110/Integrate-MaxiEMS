package io.openems.edge.meter.landis.dlms;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;


public interface LandisDlmsMeter extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * Active Power.
		 * 
		 * <ul>
		 * <li>Interface: LandisDlmsMeter
		 * <li>Type: Integer
		 * <li>Unit: Watt (W)
		 * </ul>
		 */
		ACTIVE_POWER_EXPORT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS)
				.accessMode(AccessMode.READ_ONLY)),
	
		ACTIVE_POWER_IMPORT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS)
				.accessMode(AccessMode.READ_ONLY)),

		REACTIVE_POWER_EXPORT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE_HOURS)
				.accessMode(AccessMode.READ_ONLY)),
		REACTIVE_POWER_IMPORT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE_HOURS)
				.accessMode(AccessMode.READ_ONLY));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the Channel for {@link ChannelId#ACTIVE_POWER}.
	 * 
	 * @return the Channel
	 */
	public default IntegerReadChannel getActivePowerExportChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_EXPORT);
	}

	public default IntegerReadChannel getActivePowerImportChannel() {
		return this.channel(ChannelId.ACTIVE_POWER_IMPORT);	
	}

	public default IntegerReadChannel getReactivePowerExportChannel() {
		return this.channel(ChannelId.REACTIVE_POWER_EXPORT);
	}
	
	public default IntegerReadChannel getReactivePowerImportChannel() {
		return this.channel(ChannelId.REACTIVE_POWER_IMPORT);	
	}
}