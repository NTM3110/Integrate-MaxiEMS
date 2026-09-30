package io.openems.edge.pvinverter.huawei.scada.hybrid;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;

public interface PvInverterHuaweiHybrid {
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		MODEL(Doc.of(OpenemsType.STRING)), SERIAL_NUMBER(Doc.of(OpenemsType.STRING)),
		FIRMWARE_VERSION(Doc.of(OpenemsType.STRING)), SOFTWARE_VERSION(Doc.of(OpenemsType.STRING)), PROTOCOL_VERSION(Doc.of(OpenemsType.INTEGER)), MODEL_ID(Doc.of(OpenemsType.INTEGER)),
		HARDWARE_VERSION(Doc.of(OpenemsType.STRING)), MONITORING_BOARD_SN(Doc.of(OpenemsType.STRING)),
		MONITORING_SOFTWARE_VERSION(Doc.of(OpenemsType.STRING)), PRIMARY_DSP_VERSION(Doc.of(OpenemsType.STRING)),
		SLAVE_DSP_VERSION(Doc.of(OpenemsType.STRING)), AFCI_VERSION(Doc.of(OpenemsType.STRING)),
		CPLD_REVISION(Doc.of(OpenemsType.STRING)), EL_MODULE_SOFTWARE_VERSION(Doc.of(OpenemsType.STRING)),
		AFCI_2_SOFTWARE_VERSION(Doc.of(OpenemsType.STRING)),
		S_N(Doc.of(OpenemsType.STRING)), P_N(Doc.of(OpenemsType.STRING)),
		NUMBER_PV(Doc.of(OpenemsType.INTEGER)), NUMBER_MPPT(Doc.of(OpenemsType.INTEGER)),
		RATED_POWER(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT)), RATE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.KILOWATT)), RTC_TIME(Doc.of(OpenemsType.INTEGER)),
		OPERATING_STATE(Doc.of(OpenemsType.INTEGER)), INTERNAL_TEMPERATURE(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS)),
		SINGLE_MACHINE_STATUS(Doc.of(OpenemsType.INTEGER)), MONITORING_RUNNING_STATUS(Doc.of(OpenemsType.INTEGER)),
		POWER_PROCESSING_STATUS(Doc.of(OpenemsType.INTEGER)), SN_SIGNATURE(Doc.of(OpenemsType.INTEGER)),
		LINE_VOLTAGE_AB(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), LINE_VOLTAGE_BC(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)),
		LINE_VOLTAGE_CA(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)),
		POWER_FACTOR(Doc.of(OpenemsType.FLOAT)),
		DC_BUS_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), EFFICIENCY(Doc.of(OpenemsType.FLOAT).unit(Unit.PERCENT)),
		MPPT1_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT1_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT2_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT2_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT3_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT3_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT4_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT4_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT5_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT5_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT6_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT6_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT7_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT7_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT8_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT8_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT9_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT9_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT10_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT10_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT11_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT11_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT12_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT12_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT13_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT13_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT14_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT14_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT15_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT15_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT16_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT16_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT17_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT17_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT18_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT18_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT19_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT19_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		MPPT20_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), MPPT20_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.AMPERE)),
		DC1_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC2_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC3_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC4_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC5_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC6_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC7_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC8_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC9_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC10_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC11_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC12_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC13_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC14_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC15_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC16_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC17_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC18_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC19_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)), DC20_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIVOLT)),
		DC1_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC2_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC3_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC4_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC5_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC6_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC7_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC8_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC9_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC10_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC11_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC12_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC13_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC14_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC15_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC16_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC17_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC18_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		DC19_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)), DC20_CURRENT(Doc.of(OpenemsType.FLOAT).unit(Unit.MILLIAMPERE)),
		PEAK_ACTIVE_POWER(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT)), PF(Doc.of(OpenemsType.FLOAT).unit(Unit.PERCENT)),
		TEMPERATURE(Doc.of(OpenemsType.FLOAT).unit(Unit.DEGREE_CELSIUS)), INSULATION_IMPEDANCE(Doc.of(OpenemsType.FLOAT)), DC_TOTAL(Doc.of(OpenemsType.FLOAT).unit(Unit.WATT)),
		PV_ACTIVE_POWER(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT)), ENERGY_TODAY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)),
		ENERGY_TOTAL(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)), STARTUP_TIME(Doc.of(OpenemsType.INTEGER)), SHUTDOWN_TIME(Doc.of(OpenemsType.INTEGER)), FAST_ACTIVE_POWER(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT)), TOTAL_DC_ENERGY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)), GENERATION_STATISTICS_TIME(Doc.of(OpenemsType.INTEGER)), HOURLY_ENERGY(Doc.of(OpenemsType.FLOAT).unit(Unit.KILOWATT_HOURS)),
		ALARM_COUNT(Doc.of(OpenemsType.INTEGER)), CRITICAL_ALARM_COUNT(Doc.of(OpenemsType.INTEGER)),
		MAJOR_ALARM_COUNT(Doc.of(OpenemsType.INTEGER)), MINOR_ALARM_COUNT(Doc.of(OpenemsType.INTEGER)),
		WARNING_ALARM_COUNT(Doc.of(OpenemsType.INTEGER)), ALARM_1(Doc.of(OpenemsType.INTEGER)),
		ALARM_2(Doc.of(OpenemsType.INTEGER)), ALARM_3(Doc.of(OpenemsType.INTEGER)),
		FAULT_CODE(Doc.of(OpenemsType.INTEGER));

		private final Doc doc;
		ChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return doc; }
	}
}
