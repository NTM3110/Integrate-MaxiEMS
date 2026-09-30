package io.openems.edge.pvinverter.huawei.hybrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;

import io.openems.edge.bridge.modbus.api.element.StringWordElement;
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
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
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
		name = "PV-Inverter.Huawei.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterHuaweiHybridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterHuaweiHybrid, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent,
		OpenemsComponent,
		EventHandler, ModbusSlave, TimedataProvider {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PvInverterHuaweiHybridImpl.class);

	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this);

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

	public PvInverterHuaweiHybridImpl(ConfigurationAdmin cm) {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterHuaweiHybrid.ChannelId.values() //
		);
		this.cm = cm;
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.config = config;

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
				 new FC3ReadRegistersTask(30000, Priority.LOW,
				 		m(PvInverterHuaweiHybrid.ChannelId.MODEL,
				 				new StringWordElement(30000,15)),
				 		m(PvInverterHuaweiHybrid.ChannelId.S_N,
				 				new StringWordElement(30015,10))),
				// 		m(PvInverterHuaweiHybrid.ChannelId.P_N,
				// 				new StringWordElement(30025,10))),
				 new FC3ReadRegistersTask(30073,Priority.LOW,
				// 		m(PvInverterHuaweiHybrid.ChannelId.NUMBER_PV,
				// 				new UnsignedWordElement(30071)),
				// 		m(PvInverterHuaweiHybrid.ChannelId.NUMBER_MPPT,
				// 				new UnsignedWordElement(30072)),
				 		m(PvInverterHuaweiHybrid.ChannelId.RATE_POWER,
				 				new UnsignedDoublewordElement(30073),SCALE_FACTOR_MINUS_3)),
				new FC3ReadRegistersTask(32016, Priority.HIGH,
						m(PvInverterHuaweiHybrid.ChannelId.DC1_VOLTAGE,
								new SignedWordElement(32016), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC1_CURRENT,
								new SignedWordElement(32017), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC2_VOLTAGE,
								new SignedWordElement(32018), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC2_CURRENT,
								new SignedWordElement(32019), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC3_VOLTAGE,
								new SignedWordElement(32020), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC3_CURRENT,
								new SignedWordElement(32021), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC4_VOLTAGE,
								new SignedWordElement(32022), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC4_CURRENT,
								new SignedWordElement(32023), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC5_VOLTAGE,
								new SignedWordElement(32024), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC5_CURRENT,
								new SignedWordElement(32025), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC6_VOLTAGE,
								new SignedWordElement(32026), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC6_CURRENT,
								new SignedWordElement(32027), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC7_VOLTAGE,
								new SignedWordElement(32028), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC7_CURRENT,
								new SignedWordElement(32029), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC8_VOLTAGE,
								new SignedWordElement(32030), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC8_CURRENT,
								new SignedWordElement(32031), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC9_VOLTAGE,
								new SignedWordElement(32032), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC9_CURRENT,
								new SignedWordElement(32033), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC10_VOLTAGE,
								new SignedWordElement(32034), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC10_CURRENT,
								new SignedWordElement(32035), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC11_VOLTAGE,
								new SignedWordElement(32036), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC11_CURRENT,
								new SignedWordElement(32037), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC12_VOLTAGE,
								new SignedWordElement(32038), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC12_CURRENT,
								new SignedWordElement(32039), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC13_VOLTAGE,
								new SignedWordElement(32040), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC13_CURRENT,
								new SignedWordElement(32041), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC14_VOLTAGE,
								new SignedWordElement(32042), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC14_CURRENT,
								new SignedWordElement(32043), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC15_VOLTAGE,
								new SignedWordElement(32044), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC15_CURRENT,
								new SignedWordElement(32045), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC16_VOLTAGE,
								new SignedWordElement(32046), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC16_CURRENT,
								new SignedWordElement(32047), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC17_VOLTAGE,
								new SignedWordElement(32048), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC17_CURRENT,
								new SignedWordElement(32049), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC18_VOLTAGE,
								new SignedWordElement(32050), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC18_CURRENT,
								new SignedWordElement(32051), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC19_VOLTAGE,
								new SignedWordElement(32052), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC19_CURRENT,
								new SignedWordElement(32053), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.DC20_VOLTAGE,
								new SignedWordElement(32054), SCALE_FACTOR_2),
						m(PvInverterHuaweiHybrid.ChannelId.DC20_CURRENT,
								new SignedWordElement(32055), SCALE_FACTOR_1)),

				new FC3ReadRegistersTask(32064, Priority.HIGH,
						m(PvInverterHuaweiHybrid.ChannelId.DC_TOTAL,
								new UnsignedDoublewordElement(32064).wordOrder(WordOrder.MSWLSW)),
						new DummyRegisterElement(32066,32068),
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(32069), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(32070), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(32071), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new SignedDoublewordElement(32072).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new SignedDoublewordElement(32074).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new SignedDoublewordElement(32076).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterHuaweiHybrid.ChannelId.PEAK_ACTIVE_POWER,
								new SignedDoublewordElement(32078).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new SignedDoublewordElement(32080).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(32082).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterHuaweiHybrid.ChannelId.POWER_FACTOR,
								new SignedWordElement(32084), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedWordElement(32085), SCALE_FACTOR_1),
						m(PvInverterHuaweiHybrid.ChannelId.PF,
								new UnsignedWordElement(32086), SCALE_FACTOR_MINUS_1),
						m(PvInverterHuaweiHybrid.ChannelId.TEMPERATURE,
								new SignedWordElement(32087), SCALE_FACTOR_MINUS_1)),

				new FC16WriteRegistersTask(40120, //
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC, new
						// UnsignedWordElement(43010)),
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC, new
						// UnsignedWordElement(43011)),
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_CURRENT, new
						// UnsignedWordElement(43012)),
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_CURRENT, new
						// UnsignedWordElement(43013)),
						// new DummyRegisterElement(43014,43131),
						// m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new
						// UnsignedWordElement(43132)),
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT,
								new UnsignedDoublewordElement(40120).wordOrder(WordOrder.MSWLSW)),
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT,
								new UnsignedDoublewordElement(40122).wordOrder(WordOrder.MSWLSW))),
				new FC16WriteRegistersTask(40126, //
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC, new
						// UnsignedWordElement(43010)),
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC, new
						// UnsignedWordElement(43011)),
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_CURRENT, new
						// UnsignedWordElement(43012)),
						// m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_CURRENT, new
						// UnsignedWordElement(43013)),
						// new DummyRegisterElement(43014,43131),
						// m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new
						// UnsignedWordElement(43132)),
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT,
								new UnsignedDoublewordElement(40126)),
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT,
								new UnsignedDoublewordElement(40128))));
	}

	public int getRatedPowerInWatts() {
		Integer ratePowerKw = (Integer) this.channel(PvInverterHuaweiHybrid.ChannelId.RATE_POWER).value().get();
		if (ratePowerKw != null && ratePowerKw > 0) {
			// RATE_POWER channel provides value in kW, so we multiply by 1000 to get Watts
			return ratePowerKw * 1000;
		}
		// Fallback to 10000W if RATE_POWER is null or invalid
		return 10000;
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
					this.channel(PvInverterHuaweiHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (Exception e) {
					this.channel(PvInverterHuaweiHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
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
