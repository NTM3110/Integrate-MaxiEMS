package io.openems.edge.pvinverter.SMA.hybrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.common.channel.PersistencePriority;

public interface PvInverterSMAHybrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ENERGY_PRODUCTION_CURRENTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ENERGY_PRODUCTION_LASTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.MEDIUM)),
		ENERGY_PRODUCTION_YESTERDAY(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ENERGY_PRODUCTION_YEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		ENERGY_PRODUCTION_LASTYEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.LOW)),
		APPARENT_POWER_INVERTER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.VOLT_AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_A(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_B(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		VOLTAGE_C(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		CURRENT_A(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		CURRENT_B(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		CURRENT_C(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_TEMPERATURE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_VOLTAGE_INVERTER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENT_INVERTER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_SOC(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_SOH(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENTLIMIT_CHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENTLIMIT_DISCHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_VOLTAGE_CHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_VOLTAGE_DISCHARGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_SOC2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_SOH2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_VOLTAGE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENT2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENTLIMIT_CHARGE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CURRENTLIMIT_DISCHARGE2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_CAPACITY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.AMPERE_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_POWER2(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_ENERGYCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_ENERGYCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_ENERGYDISCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_ENERGYDISCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		MODES(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.MEDIUM)),
		PF(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.MEDIUM)),
		// PV
		P_LIMIT_TYPE(Doc.of(PLimitType.values())//
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.KILOWATT)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)), //
		STATUS(Doc.of(Status.values())),

		// Power control operating modes
		Q_OPERATING_MODE(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)), // 1071=Q const kvar, 1070=Q direct
		P_OPERATING_MODE(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)), // 1077=W, 1078=% of Pmax

		PV_LIMIT_FAILED(Doc.of(Level.FAULT)//
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
