package io.openems.edge.pvinverter.solaredge;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;



public interface SolarEdge extends OpenemsComponent, ManagedSymmetricPvInverter {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		VOLTAGE_L1_L2(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_L2_L3(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_L3_L1(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		POWER_FACTOR(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.WATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.WATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_CURRENT(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_POWER(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.DECIMAL_DEGREE)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		CURRENT_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		ACTIVE_POWER_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		APPARENT_POWER_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		REACTIVE_POWER_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		POWER_FACTOR_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		ENERGY_PRODUCTION_TOTAL_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		DC1_CURRENT_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		DC1_VOLTAGE_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		DC1_POWER_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		TEMPERATURE_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		FREQUENCY_SF(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)
				.unit(Unit.NONE)),
		P_LIMIT_TYPE(Doc.of(PLimitType.values()) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT_PERC(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.PERCENT)),
		P_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.KILOWATT)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		STATUS(Doc.of(Status.values())),

		PV_LIMIT_FAILED(Doc.of(Level.FAULT) //
				.text("PV-Limit failed")),

		LIMIT_ACTIVE_POWER(Doc.of(OpenemsType.FLOAT) //
				.accessMode(AccessMode.WRITE_ONLY) //
				.unit(Unit.WATT)), //
		LIMIT_REACTIVE_POWER(Doc.of(OpenemsType.FLOAT) //
				.accessMode(AccessMode.WRITE_ONLY) //
				.unit(Unit.VOLT_AMPERE_REACTIVE)), //

		// Raw Channels for Scale Factor calculation
		RAW_CURRENT(Doc.of(OpenemsType.INTEGER)),
		RAW_CURRENT_L1(Doc.of(OpenemsType.INTEGER)),
		RAW_CURRENT_L2(Doc.of(OpenemsType.INTEGER)),
		RAW_CURRENT_L3(Doc.of(OpenemsType.INTEGER)),
		RAW_VOLTAGE_L1_L2(Doc.of(OpenemsType.INTEGER)),
		RAW_VOLTAGE_L2_L3(Doc.of(OpenemsType.INTEGER)),
		RAW_VOLTAGE_L3_L1(Doc.of(OpenemsType.INTEGER)),
		RAW_VOLTAGE_L1(Doc.of(OpenemsType.INTEGER)),
		RAW_VOLTAGE_L2(Doc.of(OpenemsType.INTEGER)),
		RAW_VOLTAGE_L3(Doc.of(OpenemsType.INTEGER)),
		RAW_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER)),
		RAW_FREQUENCY(Doc.of(OpenemsType.INTEGER)),
		RAW_APPARENT_POWER(Doc.of(OpenemsType.INTEGER)),
		RAW_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER)),
		RAW_POWER_FACTOR(Doc.of(OpenemsType.INTEGER)),
		RAW_ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.INTEGER)),
		RAW_DC1_CURRENT(Doc.of(OpenemsType.INTEGER)),
		RAW_DC1_VOLTAGE(Doc.of(OpenemsType.INTEGER)),
		RAW_DC1_POWER(Doc.of(OpenemsType.INTEGER)),
		RAW_TEMPERATURE(Doc.of(OpenemsType.INTEGER));


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
