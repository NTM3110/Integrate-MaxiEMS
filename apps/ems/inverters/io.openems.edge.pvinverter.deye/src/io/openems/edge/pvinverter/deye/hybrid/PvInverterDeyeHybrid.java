package io.openems.edge.pvinverter.deye.hybrid;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterDeyeHybrid
		extends ManagedSymmetricPvInverter, ElectricityMeter, HybridInverter, HybridInverterControl,
		HybridInverterAlarms, OpenemsComponent, EventHandler, ModbusSlave {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ENERGY_PRODUCTION_TOTAL(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_CURRENTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_LASTMONTH(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_TODAY(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_YESTERDAY(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_YEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		ENERGY_PRODUCTION_LASTYEAR(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		APPARENT_POWER_INVERTER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.VOLT_AMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_VOLTAGE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIVOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		DC1_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC2_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC3_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC4_AMPERE(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		DC_TOTAL(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.WATT)
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
				.unit(Unit.VOLT).persistencePriority(PersistencePriority.HIGH)
		),
		BATTERY_ENERGYDISCHARGE_TOTAL(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.KILOWATT_HOURS)
				.persistencePriority(PersistencePriority.HIGH)),
		BATTERY_ENERGYDISCHARGE_TODAY(Doc.of(OpenemsType.FLOAT)
				.unit(Unit.VOLT)
				.persistencePriority(PersistencePriority.HIGH)),
		MODES(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.HIGH)),
		PF(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_ONLY)
				.persistencePriority(PersistencePriority.HIGH)),
		// PV
		P_LIMIT_TYPE(Doc.of(PLimitType.values())//
				.accessMode(AccessMode.WRITE_ONLY)), //
		P_LIMIT(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.KILOWATT)),
		WATCH_DOG_TAG(Doc.of(OpenemsType.INTEGER)//
				.accessMode(AccessMode.READ_WRITE)), //
		BMS_MAX_CHARGE_CURRENT(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		BMS_MAX_DISCHARGE_CURRENT(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.MILLIAMPERE)
				.persistencePriority(PersistencePriority.HIGH)),
		BMS_COMMUNICATION_STATUS(Doc.of(OpenemsType.INTEGER)
				.persistencePriority(PersistencePriority.HIGH)),
		REMOTE_ENABLE_REQUEST(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)),
		REMOTE_ENABLE_READBACK(Doc.of(OpenemsType.INTEGER)
				.persistencePriority(PersistencePriority.HIGH)),
		HEARTBEAT_REQUEST(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.WRITE_ONLY)),
		HEARTBEAT_READBACK(Doc.of(OpenemsType.INTEGER)
				.persistencePriority(PersistencePriority.HIGH)),
		WARNING_BITMAP(Doc.of(OpenemsType.LONG)
				.persistencePriority(PersistencePriority.HIGH)),
		FAULT_BITMAP(Doc.of(OpenemsType.LONG)
				.persistencePriority(PersistencePriority.HIGH)),
		STATUS(Doc.of(Status.values())
				.persistencePriority(PersistencePriority.HIGH)),
		S_N(Doc.of(OpenemsType.STRING)
						.accessMode(AccessMode.READ_WRITE)),
		RATE_POWER(Doc.of(OpenemsType.FLOAT)
				.accessMode(AccessMode.READ_WRITE)
				.persistencePriority(PersistencePriority.HIGH)),
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

	public default IntegerReadChannel getBmsMaxChargeCurrentChannel() {
		return this.channel(ChannelId.BMS_MAX_CHARGE_CURRENT);
	}

	public default Value<Integer> getBmsMaxChargeCurrent() {
		return this.getBmsMaxChargeCurrentChannel().value();
	}

	public default IntegerReadChannel getBmsMaxDischargeCurrentChannel() {
		return this.channel(ChannelId.BMS_MAX_DISCHARGE_CURRENT);
	}

	public default Value<Integer> getBmsMaxDischargeCurrent() {
		return this.getBmsMaxDischargeCurrentChannel().value();
	}

	public default IntegerReadChannel getBmsCommunicationStatusChannel() {
		return this.channel(ChannelId.BMS_COMMUNICATION_STATUS);
	}

	public default Value<Integer> getBmsCommunicationStatus() {
		return this.getBmsCommunicationStatusChannel().value();
	}

	public default IntegerWriteChannel getRemoteEnableRequestChannel() {
		return this.channel(ChannelId.REMOTE_ENABLE_REQUEST);
	}

	public default void setRemoteEnableRequest(int value) throws OpenemsNamedException {
		this.getRemoteEnableRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getRemoteEnableReadbackChannel() {
		return this.channel(ChannelId.REMOTE_ENABLE_READBACK);
	}

	public default Value<Integer> getRemoteEnableReadback() {
		return this.getRemoteEnableReadbackChannel().value();
	}

	public default IntegerWriteChannel getHeartbeatRequestChannel() {
		return this.channel(ChannelId.HEARTBEAT_REQUEST);
	}

	public default void setHeartbeatRequest(int value) throws OpenemsNamedException {
		this.getHeartbeatRequestChannel().setNextWriteValue(value);
	}

	public default IntegerReadChannel getHeartbeatReadbackChannel() {
		return this.channel(ChannelId.HEARTBEAT_READBACK);
	}

	public default Value<Integer> getHeartbeatReadback() {
		return this.getHeartbeatReadbackChannel().value();
	}

	public default LongReadChannel getWarningBitmapChannel() {
		return this.channel(ChannelId.WARNING_BITMAP);
	}

	public default Value<Long> getWarningBitmap() {
		return this.getWarningBitmapChannel().value();
	}

	public default LongReadChannel getFaultBitmapChannel() {
		return this.channel(ChannelId.FAULT_BITMAP);
	}

	public default Value<Long> getFaultBitmap() {
		return this.getFaultBitmapChannel().value();
	}
}
