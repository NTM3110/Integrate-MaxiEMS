package io.openems.edge.pvinverter.solis.hybrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterSolisHybrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		    
		    // --- Năng lượng theo chu kỳ ---
			ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.INTEGER)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.MEDIUM)),
			ENERGY_PRODUCTION_MONTHLY(Doc.of(OpenemsType.INTEGER)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.MEDIUM)),
			ENERGY_PRODUCTION_LASTMONTHLY(Doc.of(OpenemsType.INTEGER)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.LOW)),
			ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)), // Giám sát hàng ngày
			ENERGY_PRODUCTION_YESTERDAY(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.LOW)),
		    ACTIVE_PRODUCTION_ENERGY_YEAR(Doc.of(OpenemsType.INTEGER)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.MEDIUM)),
		    ACTIVE_PRODUCTION_ENERGY_LASTYEAR(Doc.of(OpenemsType.INTEGER)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.LOW)),

		    // --- Công suất ---
		    APPARENT_POWER_INVERTER(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.VOLT_AMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),

		    // --- DC inputs ---
		    DC1_VOLTAGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIVOLT)
		            .persistencePriority(PersistencePriority.HIGH)),
		    DC2_VOLTAGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIVOLT)
		            .persistencePriority(PersistencePriority.HIGH)),
		    DC3_VOLTAGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIVOLT)
		            .persistencePriority(PersistencePriority.HIGH)),
		    DC4_VOLTAGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIVOLT)
		            .persistencePriority(PersistencePriority.HIGH)),

		    DC1_CURRENT(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),
		    DC2_CURRENT(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),
		    DC3_CURRENT(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),
		    DC4_CURRENT(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),

		    DC_TOTAL(Doc.of(OpenemsType.INTEGER)
		            .unit(Unit.WATT)
		            .persistencePriority(PersistencePriority.HIGH)),

		    // --- Nhiệt độ ---
		    TEMPERATURE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.DEGREE_CELSIUS)
		            .persistencePriority(PersistencePriority.HIGH)),

		    // --- Pin/Battery ---
		    BATTERY_VOLTAGE_INVERTER(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIVOLT)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_CURRENT_INVERTER(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_SOC(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.PERCENT)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_SOH(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.PERCENT)
		            .persistencePriority(PersistencePriority.MEDIUM)),

		    BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIVOLT)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_CURRENT(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.HIGH)),

		    BATTERY_CURRENTLIMIT_CHARGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.MEDIUM)),
		    BATTERY_CURRENTLIMIT_DISCHARGE(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.MILLIAMPERE)
		            .persistencePriority(PersistencePriority.MEDIUM)),

		    BATTERY_POWER(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.WATT)
		            .persistencePriority(PersistencePriority.HIGH)),

		    BATTERY_ENERGYCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.MEDIUM)),
		    BATTERY_ENERGYCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_ENERGYCHARGE_YESTERDAY(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_ENERGYDISCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.MEDIUM)),
		    BATTERY_ENERGYDISCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    BATTERY_ENERGYDISCHARGE_YESTERDAY(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    TOTAL_ENERGY_IMPORT_GRID(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    TODAY_ENERGY_IMPORT_GRID(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    YESTERDAY_ENERGY_IMPORT_GRID(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    TOTAL_ENERGY_FED_GRID(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    TODAY_ENERGY_FED_GRID(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    YESTERDAY_ENERGY_FED_GRID(Doc.of(OpenemsType.FLOAT)
		            .unit(Unit.KILOWATT_HOURS)
		            .persistencePriority(PersistencePriority.HIGH)),
		    // --- Trạng thái hoạt động ---
		    MODES(Doc.of(OpenemsType.INTEGER)
		            .accessMode(AccessMode.READ_ONLY)
		            .persistencePriority(PersistencePriority.MEDIUM)),
		    PF(Doc.of(OpenemsType.FLOAT)
		            .accessMode(AccessMode.READ_ONLY)
		            .persistencePriority(PersistencePriority.HIGH)),
		// PV
		P_LIMIT_TYPE(Doc.of(PLimitType.values()) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.KILOWATT)
				.accessMode(AccessMode.READ_WRITE)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE)), //
		STATUS(Doc.of(Status.values())),

		PV_LIMIT_FAILED(Doc.of(Level.FAULT) //
				.text("PV-Limit failed"));

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
