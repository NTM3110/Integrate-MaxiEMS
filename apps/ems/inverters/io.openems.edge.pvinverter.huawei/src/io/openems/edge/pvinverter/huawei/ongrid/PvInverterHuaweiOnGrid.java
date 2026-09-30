package io.openems.edge.pvinverter.huawei.ongrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.common.channel.PersistencePriority;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterHuaweiOnGrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
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
		DC5_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC6_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC7_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC8_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC9_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC10_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC11_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC12_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC13_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC14_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC15_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC16_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC17_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC18_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC19_VOLTAGE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC20_VOLTAGE(Doc.of(OpenemsType.FLOAT)
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
		DC5_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC6_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC7_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC8_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC9_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC10_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC11_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC12_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC13_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC14_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC15_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC16_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC17_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC18_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC19_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC20_CURRENT(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),

		PEAK_ACTIVE_POWER(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT)
				.persistencePriority(PersistencePriority.HIGH)),
		PF(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.HIGH)),
		TEMPERATURE(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.DEGREE_CELSIUS)
				.persistencePriority(PersistencePriority.HIGH)),
		POWER_FACTOR(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.PERCENT)
				.persistencePriority(PersistencePriority.MEDIUM)),
		DC_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.WATT)
				.persistencePriority(PersistencePriority.HIGH)),

		P_LIMIT_TYPE(Doc.of(PLimitType.values())//
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT_PERC(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)//
				.unit(Unit.PERCENT)),
		P_LIMIT(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.KILOWATT)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)), //
		STATUS(Doc.of(Status.values())),

		PV_LIMIT_FAILED(Doc.of(Level.FAULT)//
				.text("PV-Limit failed")),
		MODEL(Doc.of(OpenemsType.STRING)
				.accessMode(AccessMode.READ_WRITE)),
		S_N(Doc.of(OpenemsType.STRING)
				.accessMode(AccessMode.READ_WRITE)),
		P_N(Doc.of(OpenemsType.STRING)
				.accessMode(AccessMode.READ_WRITE)),
		NUMBER_PV(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)),
		NUMBER_MPPT(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_WRITE)),
		RATE_POWER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT));

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

