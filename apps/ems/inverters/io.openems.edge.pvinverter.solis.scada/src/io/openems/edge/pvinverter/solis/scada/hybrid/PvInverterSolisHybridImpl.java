package io.openems.edge.pvinverter.solis.scada.hybrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PV-Inverter.Solis.SCADA.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterSolisHybridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSolisHybrid, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent,
		OpenemsComponent,
		EventHandler, ModbusSlave, TimedataProvider {

	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this);

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PvInverterSolisHybridImpl.class);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	protected Config config;

	public PvInverterSolisHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterSolisHybrid.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.config = config;
		this._setMaxApparentPower(config.maxActivePower());

		// Stop if component is disabled
		if (!config.enabled()) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				new FC4ReadInputRegistersTask(33000, Priority.LOW,
						m(PvInverterSolisHybrid.ChannelId.DEVICE_MODEL, new UnsignedWordElement(33000)),
						m(PvInverterSolisHybrid.ChannelId.DEVICE_FIRMWARE_VERSION, new UnsignedWordElement(33001)),
						new DummyRegisterElement(33002, 33002),
						m(PvInverterSolisHybrid.ChannelId.DEVICE_PROTOCOL_VERSION, new UnsignedWordElement(33003)),
						m(PvInverterSolisHybrid.ChannelId.DEVICE_SERIAL_NUMBER, new StringWordElement(33004, 16)),
						new DummyRegisterElement(33020, 33021),
						m(PvInverterSolisHybrid.ChannelId.RTC_YEAR, new UnsignedWordElement(33022)),
						m(PvInverterSolisHybrid.ChannelId.RTC_MONTH, new UnsignedWordElement(33023)),
						m(PvInverterSolisHybrid.ChannelId.RTC_DAY, new UnsignedWordElement(33024)),
						m(PvInverterSolisHybrid.ChannelId.RTC_HOUR, new UnsignedWordElement(33025)),
						m(PvInverterSolisHybrid.ChannelId.RTC_MINUTE, new UnsignedWordElement(33026)),
						m(PvInverterSolisHybrid.ChannelId.RTC_SECOND, new UnsignedWordElement(33027))),
				// =====================================================================
				// PV Input Section
				// =====================================================================
				new FC4ReadInputRegistersTask(33029, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(33029).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.ENERGY_PRODUCTION_MONTHLY,
								new UnsignedDoublewordElement(33031).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.ENERGY_PRODUCTION_LASTMONTHLY,
								new UnsignedDoublewordElement(33033).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.ENERGY_PRODUCTION_TODAY,
								new UnsignedWordElement(33035), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.ENERGY_PRODUCTION_YESTERDAY,
								new UnsignedWordElement(33036), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_YEAR,
								new UnsignedDoublewordElement(33037).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.ACTIVE_PRODUCTION_ENERGY_LASTYEAR,
								new UnsignedDoublewordElement(33039).wordOrder(WordOrder.MSWLSW))),

				new FC4ReadInputRegistersTask(33049, Priority.HIGH,
						m(PvInverterSolisHybrid.ChannelId.DC1_VOLTAGE,
								new UnsignedWordElement(33049), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC1_CURRENT,
								new UnsignedWordElement(33050), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC2_VOLTAGE,
								new UnsignedWordElement(33051), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC2_CURRENT,
								new UnsignedWordElement(33052), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC3_VOLTAGE,
								new UnsignedWordElement(33053), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC3_CURRENT,
								new UnsignedWordElement(33054), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC4_VOLTAGE,
								new UnsignedWordElement(33055), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.DC4_CURRENT,
								new UnsignedWordElement(33056), SCALE_FACTOR_2),

						m(PvInverterSolisHybrid.ChannelId.DC_TOTAL,
								new UnsignedDoublewordElement(33057).wordOrder(WordOrder.MSWLSW))),
				new FC4ReadInputRegistersTask(33067, Priority.HIGH,
						m(PvInverterSolisHybrid.ChannelId.APPARENT_POWER_INVERTER,
								new UnsignedWordElement(33067), SCALE_FACTOR_1),
						new DummyRegisterElement(33068, 33072),

						// =================================================================
						// AC Side (Grid / Output)
						// =================================================================
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(33073), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(33074), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(33075), SCALE_FACTOR_2),

						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedWordElement(33076), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedWordElement(33077), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedWordElement(33078), SCALE_FACTOR_2),

						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new SignedDoublewordElement(33079).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(33081).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new SignedDoublewordElement(33083).wordOrder(WordOrder.MSWLSW)),

						new DummyRegisterElement(33085, 33092),

						m(PvInverterSolisHybrid.ChannelId.TEMPERATURE,
								new SignedWordElement(33093), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedWordElement(33094), SCALE_FACTOR_1)),

				new FC4ReadInputRegistersTask(33105, Priority.HIGH,
						m(PvInverterSolisHybrid.ChannelId.PF, new SignedWordElement(33105), SCALE_FACTOR_MINUS_3),
						new DummyRegisterElement(33106, 33110),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_BMS_COMM_STATUS, new UnsignedWordElement(33111)),
						new DummyRegisterElement(33112, 33120),
						m(PvInverterSolisHybrid.ChannelId.MODES, new UnsignedWordElement(33121))),
						
				// =====================================================================
				// Battery Section
				// =====================================================================
				new FC4ReadInputRegistersTask(33133, Priority.HIGH,
						m(PvInverterSolisHybrid.ChannelId.BATTERY_VOLTAGE_INVERTER,
								new UnsignedWordElement(33133), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_CURRENT_INVERTER,
								new SignedWordElement(33134), SCALE_FACTOR_2),
						new DummyRegisterElement(33135, 33138),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_SOC,
								new UnsignedWordElement(33139)),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_SOH,
								new UnsignedWordElement(33140)),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_VOLTAGE,
								new UnsignedWordElement(33141), SCALE_FACTOR_1),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_CURRENT,
								new SignedWordElement(33142), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_CURRENTLIMIT_CHARGE,
								new UnsignedWordElement(33143), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_CURRENTLIMIT_DISCHARGE,
								new UnsignedWordElement(33144), SCALE_FACTOR_2),
						new DummyRegisterElement(33145, 33146),
						m(PvInverterSolisHybrid.ChannelId.LOAD_ACTIVE_POWER_TOTAL,
								new UnsignedWordElement(33147)),
						m(PvInverterSolisHybrid.ChannelId.EPS_ACTIVE_POWER_TOTAL,
								new UnsignedWordElement(33148)),
						new DummyRegisterElement(33149, 33149),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_POWER,
								new SignedDoublewordElement(33150).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.GRID_PORT_ACTIVE_POWER,
								new SignedDoublewordElement(33152).wordOrder(WordOrder.MSWLSW)),
						new DummyRegisterElement(33154, 33160),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_ENERGYCHARGE_TOTAL,
								new UnsignedDoublewordElement(33161).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_ENERGYCHARGE_TODAY,
								new UnsignedWordElement(33163), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_ENERGYCHARGE_YESTERDAY,
								new UnsignedWordElement(33164), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_ENERGYDISCHARGE_TOTAL,
								new UnsignedDoublewordElement(33165).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_ENERGYDISCHARGE_TODAY,
								new UnsignedWordElement(33167), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.BATTERY_ENERGYDISCHARGE_YESTERDAY,
								new UnsignedWordElement(33168), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.TOTAL_ENERGY_IMPORT_GRID,
								new UnsignedDoublewordElement(33169).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.TODAY_ENERGY_IMPORT_GRID,
								new UnsignedWordElement(33171), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.YESTERDAY_ENERGY_IMPORT_GRID,
								new UnsignedWordElement(33172), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.TOTAL_ENERGY_FED_GRID,
								new UnsignedDoublewordElement(33173).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.TODAY_ENERGY_FED_GRID,
								new UnsignedWordElement(33175), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisHybrid.ChannelId.YESTERDAY_ENERGY_FED_GRID,
								new UnsignedWordElement(33176), SCALE_FACTOR_MINUS_1)),

				new FC4ReadInputRegistersTask(33251, Priority.HIGH,
						m(PvInverterSolisHybrid.ChannelId.METER_VOLTAGE_L1,
								new UnsignedWordElement(33251), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.METER_CURRENT_L1,
								new UnsignedWordElement(33252), SCALE_FACTOR_1),
						m(PvInverterSolisHybrid.ChannelId.METER_VOLTAGE_L2,
								new UnsignedWordElement(33253), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.METER_CURRENT_L2,
								new UnsignedWordElement(33254), SCALE_FACTOR_1),
						m(PvInverterSolisHybrid.ChannelId.METER_VOLTAGE_L3,
								new UnsignedWordElement(33255), SCALE_FACTOR_2),
						m(PvInverterSolisHybrid.ChannelId.METER_CURRENT_L3,
								new UnsignedWordElement(33256), SCALE_FACTOR_1),
						m(PvInverterSolisHybrid.ChannelId.METER_ACTIVE_POWER_L1,
								new SignedDoublewordElement(33257).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_ACTIVE_POWER_L2,
								new SignedDoublewordElement(33259).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_ACTIVE_POWER_L3,
								new SignedDoublewordElement(33261).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_ACTIVE_POWER_TOTAL,
								new SignedDoublewordElement(33263).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_REACTIVE_POWER_L1,
								new SignedDoublewordElement(33265).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_REACTIVE_POWER_L2,
								new SignedDoublewordElement(33267).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_REACTIVE_POWER_L3,
								new SignedDoublewordElement(33269).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisHybrid.ChannelId.METER_REACTIVE_POWER_TOTAL,
								new SignedDoublewordElement(33271).wordOrder(WordOrder.MSWLSW)),
						new DummyRegisterElement(33273, 33280),
						m(PvInverterSolisHybrid.ChannelId.METER_POWER_FACTOR,
								new SignedWordElement(33281), SCALE_FACTOR_MINUS_2),
						m(PvInverterSolisHybrid.ChannelId.METER_FREQUENCY,
								new UnsignedWordElement(33282), SCALE_FACTOR_1),
						m(PvInverterSolisHybrid.ChannelId.METER_TOTAL_ENERGY_IMPORT_GRID,
								new UnsignedDoublewordElement(33283).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_2),
						m(PvInverterSolisHybrid.ChannelId.METER_TOTAL_ENERGY_FED_GRID,
								new UnsignedDoublewordElement(33285).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_2)),
		
				new FC16WriteRegistersTask(43010, //
						m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC, new UnsignedWordElement(43010)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC, new UnsignedWordElement(43011)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_CURRENT, new UnsignedWordElement(43012)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_CURRENT, new UnsignedWordElement(43013))),
				new FC16WriteRegistersTask(43132,
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new UnsignedWordElement(43132)),
						m(PvInverterSolisHybrid.ChannelId.P_LIMIT, new UnsignedWordElement(43133)),  // Scaled: 1 unit = 10W
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT, new UnsignedWordElement(43134))));
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
			case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
				try {
					this.setPvLimitHandler.run();
					this.setReactivePowerLimitHandler.run();
					this.channel(PvInverterSolisHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (Exception e) {
					this.channel(PvInverterSolisHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
					LOG.error("Error applying power limit: {}", e.getMessage());
				}
				break;
		}
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

}
