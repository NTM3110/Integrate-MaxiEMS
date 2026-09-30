package io.openems.edge.pvinverter.huawei.scada.ongrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;
import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.*;
import io.openems.edge.bridge.modbus.api.element.*;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.*;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(name = "PV-Inverter.Huawei.SCADA.OnGrid", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, property = "type=PRODUCTION")
public class PvInverterHuaweiOnGridImpl extends AbstractOpenemsModbusComponent implements PvInverterHuaweiOnGrid,
		ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent, OpenemsComponent, ModbusSlave {
	@Reference private ConfigurationAdmin cm;
	@Override @Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) { super.setModbus(modbus); }
	public PvInverterHuaweiOnGridImpl() { super(OpenemsComponent.ChannelId.values(), ModbusComponent.ChannelId.values(),
		ElectricityMeter.ChannelId.values(), ManagedSymmetricPvInverter.ChannelId.values(), PvInverterHuaweiOnGrid.ChannelId.values()); }
	@Activate private void activate(ComponentContext context, Config config) throws OpenemsException {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), cm, "Modbus", config.modbus_id());
	}
	@Override protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
			new FC3ReadRegistersTask(30000, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.MODEL, new StringWordElement(30000, 15)), m(PvInverterHuaweiOnGrid.ChannelId.S_N, new StringWordElement(30015, 10))),
			new FC3ReadRegistersTask(30025, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.P_N, new StringWordElement(30025, 10))),
			new FC3ReadRegistersTask(30035, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.FIRMWARE_VERSION, new StringWordElement(30035, 15))),
			new FC3ReadRegistersTask(30050, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.SOFTWARE_VERSION, new StringWordElement(30050, 15))),
			new FC3ReadRegistersTask(30068, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.PROTOCOL_VERSION, new UnsignedDoublewordElement(30068))),
			new FC3ReadRegistersTask(30071, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.NUMBER_PV, new UnsignedWordElement(30071)), m(PvInverterHuaweiOnGrid.ChannelId.NUMBER_MPPT, new UnsignedWordElement(30072))),
			new FC3ReadRegistersTask(30070, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.MODEL_ID, new UnsignedWordElement(30070))),
			new FC3ReadRegistersTask(30073, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.RATE_POWER, new UnsignedDoublewordElement(30073), SCALE_FACTOR_MINUS_2)),
			new FC3ReadRegistersTask(30075, Priority.LOW, m(ManagedSymmetricPvInverter.ChannelId.MAX_ACTIVE_POWER, new UnsignedDoublewordElement(30075), SCALE_FACTOR_MINUS_2), m(ManagedSymmetricPvInverter.ChannelId.MAX_APPARENT_POWER, new UnsignedDoublewordElement(30077), SCALE_FACTOR_MINUS_2), m(ManagedSymmetricPvInverter.ChannelId.MAX_REACTIVE_POWER, new SignedDoublewordElement(30079), SCALE_FACTOR_MINUS_2)),
			new FC3ReadRegistersTask(31000, Priority.LOW,
				m(PvInverterHuaweiOnGrid.ChannelId.HARDWARE_VERSION, new StringWordElement(31000, 15)),
				m(PvInverterHuaweiOnGrid.ChannelId.MONITORING_BOARD_SN, new StringWordElement(31015, 10)),
				m(PvInverterHuaweiOnGrid.ChannelId.MONITORING_SOFTWARE_VERSION, new StringWordElement(31025, 15)),
				m(PvInverterHuaweiOnGrid.ChannelId.PRIMARY_DSP_VERSION, new StringWordElement(31040, 15)),
				m(PvInverterHuaweiOnGrid.ChannelId.SLAVE_DSP_VERSION, new StringWordElement(31055, 15)),
				m(PvInverterHuaweiOnGrid.ChannelId.CPLD_REVISION, new StringWordElement(31070, 15)),
				m(PvInverterHuaweiOnGrid.ChannelId.AFCI_VERSION, new StringWordElement(31085, 15))),
			new FC3ReadRegistersTask(31100, Priority.LOW,
				new DummyRegisterElement(31100, 31129),
				m(PvInverterHuaweiOnGrid.ChannelId.EL_MODULE_SOFTWARE_VERSION, new StringWordElement(31130, 15)),
				m(PvInverterHuaweiOnGrid.ChannelId.AFCI_2_SOFTWARE_VERSION, new StringWordElement(31145, 15))),
			new FC3ReadRegistersTask(32000, Priority.HIGH,
				m(PvInverterHuaweiOnGrid.ChannelId.SINGLE_MACHINE_STATUS, new UnsignedWordElement(32000)),
				new DummyRegisterElement(32001, 32001),
				m(PvInverterHuaweiOnGrid.ChannelId.MONITORING_RUNNING_STATUS, new UnsignedWordElement(32002)),
				m(PvInverterHuaweiOnGrid.ChannelId.POWER_PROCESSING_STATUS, new UnsignedDoublewordElement(32003))),
			new FC3ReadRegistersTask(32008, Priority.LOW,
				m(PvInverterHuaweiOnGrid.ChannelId.ALARM_1, new UnsignedWordElement(32008)),
				m(PvInverterHuaweiOnGrid.ChannelId.ALARM_2, new UnsignedWordElement(32009)),
				m(PvInverterHuaweiOnGrid.ChannelId.ALARM_3, new UnsignedWordElement(32010)),
				new DummyRegisterElement(32011, 32014),
				m(PvInverterHuaweiOnGrid.ChannelId.SN_SIGNATURE, new UnsignedWordElement(32015))),
			new FC3ReadRegistersTask(32016, Priority.HIGH,
				m(PvInverterHuaweiOnGrid.ChannelId.DC1_VOLTAGE, new SignedWordElement(32016), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC1_CURRENT, new SignedWordElement(32017), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC2_VOLTAGE, new SignedWordElement(32018), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC2_CURRENT, new SignedWordElement(32019), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC3_VOLTAGE, new SignedWordElement(32020), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC3_CURRENT, new SignedWordElement(32021), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC4_VOLTAGE, new SignedWordElement(32022), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC4_CURRENT, new SignedWordElement(32023), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC5_VOLTAGE, new SignedWordElement(32024), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC5_CURRENT, new SignedWordElement(32025), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC6_VOLTAGE, new SignedWordElement(32026), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC6_CURRENT, new SignedWordElement(32027), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC7_VOLTAGE, new SignedWordElement(32028), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC7_CURRENT, new SignedWordElement(32029), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC8_VOLTAGE, new SignedWordElement(32030), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC8_CURRENT, new SignedWordElement(32031), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC9_VOLTAGE, new SignedWordElement(32032), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC9_CURRENT, new SignedWordElement(32033), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC10_VOLTAGE, new SignedWordElement(32034), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC10_CURRENT, new SignedWordElement(32035), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC11_VOLTAGE, new SignedWordElement(32036), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC11_CURRENT, new SignedWordElement(32037), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC12_VOLTAGE, new SignedWordElement(32038), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC12_CURRENT, new SignedWordElement(32039), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC13_VOLTAGE, new SignedWordElement(32040), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC13_CURRENT, new SignedWordElement(32041), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC14_VOLTAGE, new SignedWordElement(32042), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC14_CURRENT, new SignedWordElement(32043), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC15_VOLTAGE, new SignedWordElement(32044), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC15_CURRENT, new SignedWordElement(32045), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC16_VOLTAGE, new SignedWordElement(32046), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC16_CURRENT, new SignedWordElement(32047), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC17_VOLTAGE, new SignedWordElement(32048), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC17_CURRENT, new SignedWordElement(32049), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC18_VOLTAGE, new SignedWordElement(32050), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC18_CURRENT, new SignedWordElement(32051), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC19_VOLTAGE, new SignedWordElement(32052), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC19_CURRENT, new SignedWordElement(32053), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.DC20_VOLTAGE, new SignedWordElement(32054), SCALE_FACTOR_2), m(PvInverterHuaweiOnGrid.ChannelId.DC20_CURRENT, new SignedWordElement(32055), SCALE_FACTOR_1)),
			new FC3ReadRegistersTask(32064, Priority.HIGH,
				m(PvInverterHuaweiOnGrid.ChannelId.PV_ACTIVE_POWER, new SignedDoublewordElement(32064), SCALE_FACTOR_MINUS_2),
				m(PvInverterHuaweiOnGrid.ChannelId.LINE_VOLTAGE_AB, new UnsignedWordElement(32066), SCALE_FACTOR_MINUS_1),
				m(PvInverterHuaweiOnGrid.ChannelId.LINE_VOLTAGE_BC, new UnsignedWordElement(32067), SCALE_FACTOR_MINUS_1),
				m(PvInverterHuaweiOnGrid.ChannelId.LINE_VOLTAGE_CA, new UnsignedWordElement(32068), SCALE_FACTOR_MINUS_1),
				m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(32069), SCALE_FACTOR_1), m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(32070), SCALE_FACTOR_1), m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(32071), SCALE_FACTOR_1),
				m(ElectricityMeter.ChannelId.CURRENT_L1, new SignedDoublewordElement(32072), SCALE_FACTOR_1), m(ElectricityMeter.ChannelId.CURRENT_L2, new SignedDoublewordElement(32074), SCALE_FACTOR_1), m(ElectricityMeter.ChannelId.CURRENT_L3, new SignedDoublewordElement(32076), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.PEAK_ACTIVE_POWER, new SignedDoublewordElement(32078), SCALE_FACTOR_MINUS_2),
				m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(32080), SCALE_FACTOR_1), m(ElectricityMeter.ChannelId.REACTIVE_POWER, new SignedDoublewordElement(32082), SCALE_FACTOR_1),
				m(PvInverterHuaweiOnGrid.ChannelId.POWER_FACTOR, new SignedWordElement(32084), SCALE_FACTOR_MINUS_3), m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(32085), SCALE_FACTOR_1), m(PvInverterHuaweiOnGrid.ChannelId.EFFICIENCY, new UnsignedWordElement(32086), SCALE_FACTOR_MINUS_1), m(PvInverterHuaweiOnGrid.ChannelId.TEMPERATURE, new SignedWordElement(32087), SCALE_FACTOR_MINUS_1), m(PvInverterHuaweiOnGrid.ChannelId.INSULATION_IMPEDANCE, new UnsignedWordElement(32088), SCALE_FACTOR_MINUS_3), m(PvInverterHuaweiOnGrid.ChannelId.OPERATING_STATE, new UnsignedWordElement(32089)), m(PvInverterHuaweiOnGrid.ChannelId.FAULT_CODE, new UnsignedWordElement(32090))),
			new FC3ReadRegistersTask(32064, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.DC_TOTAL, new SignedDoublewordElement(32064), SCALE_FACTOR_MINUS_2)),
			new FC3ReadRegistersTask(32091, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.STARTUP_TIME, new UnsignedDoublewordElement(32091)), m(PvInverterHuaweiOnGrid.ChannelId.SHUTDOWN_TIME, new UnsignedDoublewordElement(32093)), m(PvInverterHuaweiOnGrid.ChannelId.FAST_ACTIVE_POWER, new SignedDoublewordElement(32095), SCALE_FACTOR_MINUS_2)),
			new FC3ReadRegistersTask(32106, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.ENERGY_TOTAL, new UnsignedDoublewordElement(32106), SCALE_FACTOR_MINUS_2), m(PvInverterHuaweiOnGrid.ChannelId.TOTAL_DC_ENERGY, new UnsignedDoublewordElement(32108), SCALE_FACTOR_MINUS_2), m(PvInverterHuaweiOnGrid.ChannelId.GENERATION_STATISTICS_TIME, new UnsignedDoublewordElement(32110)), m(PvInverterHuaweiOnGrid.ChannelId.HOURLY_ENERGY, new UnsignedDoublewordElement(32112), SCALE_FACTOR_MINUS_2), m(PvInverterHuaweiOnGrid.ChannelId.ENERGY_TODAY, new UnsignedDoublewordElement(32114), SCALE_FACTOR_MINUS_2), new DummyRegisterElement(32116, 32150),
				m(PvInverterHuaweiOnGrid.ChannelId.CRITICAL_ALARM_COUNT, new UnsignedWordElement(32151)),
				m(PvInverterHuaweiOnGrid.ChannelId.MAJOR_ALARM_COUNT, new UnsignedWordElement(32152)),
				m(PvInverterHuaweiOnGrid.ChannelId.MINOR_ALARM_COUNT, new UnsignedWordElement(32153)),
				m(PvInverterHuaweiOnGrid.ChannelId.WARNING_ALARM_COUNT, new UnsignedWordElement(32154))),
			new FC3ReadRegistersTask(32176, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.DC_BUS_VOLTAGE, new SignedWordElement(32176), SCALE_FACTOR_MINUS_1)),
			new FC3ReadRegistersTask(40000, Priority.LOW, m(PvInverterHuaweiOnGrid.ChannelId.RTC_TIME, new UnsignedDoublewordElement(40000))),
			new FC3ReadRegistersTask(40126, Priority.LOW, m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT, new UnsignedDoublewordElement(40126))));
	}
	@Override public MeterType getMeterType() { return MeterType.PRODUCTION; }
	@Override public String debugLog() {
		return "HuaweiOnGrid"
				+ " | ActivePower=" + this.getActivePower().asString()
				+ " | VoltageL1=" + this.getVoltageL1().asString()
				+ " | VoltageL2=" + this.getVoltageL2().asString()
				+ " | VoltageL3=" + this.getVoltageL3().asString()
				+ " | CurrentL1=" + this.getCurrentL1().asString()
				+ " | Frequency=" + this.getFrequency().asString()
				+ " | EnergyTotal=" + this.channel(PvInverterHuaweiOnGrid.ChannelId.ENERGY_TOTAL).value().asString()
				+ " | EnergyToday=" + this.channel(PvInverterHuaweiOnGrid.ChannelId.ENERGY_TODAY).value().asString()
				+ " | FaultCode=" + this.channel(PvInverterHuaweiOnGrid.ChannelId.FAULT_CODE).value().asString();
	}
	@Override public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) { return new ModbusSlaveTable(OpenemsComponent.getModbusSlaveNatureTable(accessMode), ElectricityMeter.getModbusSlaveNatureTable(accessMode), ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode)); }
}

