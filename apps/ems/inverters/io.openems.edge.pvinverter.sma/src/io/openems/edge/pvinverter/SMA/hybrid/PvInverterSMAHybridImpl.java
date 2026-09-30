package io.openems.edge.pvinverter.SMA.hybrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
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
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedQuadruplewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
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
		name = "PV-Inverter.SMA.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterSMAHybridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSMAHybrid, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent, OpenemsComponent,
		EventHandler, ModbusSlave, TimedataProvider {

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

	public PvInverterSMAHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterSMAHybrid.ChannelId.values() //
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
				new FC3ReadRegistersTask(30513, Priority.LOW,
						m(PvInverterSMAHybrid.ChannelId.ENERGY_PRODUCTION_TOTAL,
								new SignedQuadruplewordElement(30513), SCALE_FACTOR_3),
						m(PvInverterSMAHybrid.ChannelId.ENERGY_PRODUCTION_TODAY,
								new SignedQuadruplewordElement(30517))),
				new FC3ReadRegistersTask(30769, Priority.HIGH,
						m(PvInverterSMAHybrid.ChannelId.DC1_CURRENT,
								new SignedDoublewordElement(30769).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSMAHybrid.ChannelId.DC1_VOLTAGE,
								new SignedDoublewordElement(30771).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						m(PvInverterSMAHybrid.ChannelId.DC1_POWER,
								new SignedDoublewordElement(30773).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new SignedDoublewordElement(30775).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1,
								new SignedDoublewordElement(30777).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2,
								new SignedDoublewordElement(30779).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3,
								new SignedDoublewordElement(30781).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedDoublewordElement(30783).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedDoublewordElement(30785).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedDoublewordElement(30787).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						new DummyRegisterElement(30789, 30794),
						m(ElectricityMeter.ChannelId.CURRENT,
								new UnsignedDoublewordElement(30795).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedDoublewordElement(30797).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedDoublewordElement(30799).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedDoublewordElement(30801).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedDoublewordElement(30803).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(30805).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1,
								new SignedDoublewordElement(30807).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2,
								new SignedDoublewordElement(30809).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3,
								new SignedDoublewordElement(30811).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new SignedDoublewordElement(30813).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L1,
								new SignedDoublewordElement(30815).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L2,
								new SignedDoublewordElement(30817).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L3,
								new SignedDoublewordElement(30819).wordOrder(WordOrder.MSWLSW))),
				new FC3ReadRegistersTask(30953, Priority.HIGH,
						m(PvInverterSMAHybrid.ChannelId.TEMPERATURE,
								new UnsignedDoublewordElement(30953).wordOrder(WordOrder.MSWLSW)),
						new DummyRegisterElement(30955, 30956),
						m(PvInverterSMAHybrid.ChannelId.DC2_CURRENT,
								new UnsignedDoublewordElement(30957).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3),
						m(PvInverterSMAHybrid.ChannelId.DC2_VOLTAGE,
								new UnsignedDoublewordElement(30959).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_2),
						m(PvInverterSMAHybrid.ChannelId.DC2_POWER,
								new UnsignedDoublewordElement(30961).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSMAHybrid.ChannelId.DC3_CURRENT,
								new UnsignedDoublewordElement(30963).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3),
						m(PvInverterSMAHybrid.ChannelId.DC3_VOLTAGE,
								new UnsignedDoublewordElement(30965).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_2),
						m(PvInverterSMAHybrid.ChannelId.DC3_POWER,
								new UnsignedDoublewordElement(30967).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSMAHybrid.ChannelId.DC4_CURRENT,
								new UnsignedDoublewordElement(30969).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3),
						m(PvInverterSMAHybrid.ChannelId.DC4_VOLTAGE,
								new UnsignedDoublewordElement(30971).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_2),
						m(PvInverterSMAHybrid.ChannelId.DC4_POWER,
								new UnsignedDoublewordElement(30973).wordOrder(WordOrder.MSWLSW)),
						new DummyRegisterElement(30975, 30982),
						m(PvInverterSMAHybrid.ChannelId.DC_TOTAL,
								new UnsignedDoublewordElement(30983).wordOrder(WordOrder.MSWLSW)),
						new DummyRegisterElement(30985,30988),
						m(PvInverterSMAHybrid.ChannelId.BATTERY_VOLTAGE_CHARGE,
								new UnsignedDoublewordElement(30989).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3),
						m(PvInverterSMAHybrid.ChannelId.BATTERY_VOLTAGE_DISCHARGE,
								new UnsignedDoublewordElement(30991).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3)

				),

				// =====================================================================
				// Battery Section
				// =====================================================================
				new FC3ReadRegistersTask(30843, Priority.HIGH,
						m(PvInverterSMAHybrid.ChannelId.BATTERY_CURRENT,
								new UnsignedDoublewordElement(30843).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_MINUS_3),
						new DummyRegisterElement(30845, 30846),
						m(PvInverterSMAHybrid.ChannelId.BATTERY_SOC,
								new UnsignedDoublewordElement(30847).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSMAHybrid.ChannelId.BATTERY_TEMPERATURE,
								new SignedDoublewordElement(30849).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSMAHybrid.ChannelId.BATTERY_VOLTAGE,
								new UnsignedDoublewordElement(30851).wordOrder(WordOrder.MSWLSW),
								SCALE_FACTOR_MINUS_2)),
				new FC16WriteRegistersTask(40051,
						m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_SOC, new UnsignedDoublewordElement(40051)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_SOC,
								new UnsignedDoublewordElement(40053))),
				new FC16WriteRegistersTask(40081, //
						m(ManagedSymmetricPvInverter.ChannelId.MAX_CHARGE_CURRENT,
								new UnsignedDoublewordElement(40081)),
						m(ManagedSymmetricPvInverter.ChannelId.MAX_DISCHARGE_CURRENT,
								new UnsignedDoublewordElement(40083))),
				// SMA Reactive Power Control: 40200=mode, 40202=VAr, 40204=%
				new FC16WriteRegistersTask(40200, //
						m(PvInverterSMAHybrid.ChannelId.Q_OPERATING_MODE,
								new UnsignedDoublewordElement(40200)), // 1070=Q direct, 1071=Q const kvar
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT,
								new SignedDoublewordElement(40202)), // Q setpoint in VAr
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT_PERCENT,
								new SignedDoublewordElement(40204))), // Q setpoint in % (FIX1 = x10)
				// SMA Active Power Control: 40210=mode, 40212=W, 40214=%
				new FC16WriteRegistersTask(40210, //
						m(PvInverterSMAHybrid.ChannelId.P_OPERATING_MODE,
								new UnsignedDoublewordElement(40210)), // 1077=W, 1078=% of Pmax
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT,
								new UnsignedDoublewordElement(40212)), // P setpoint in W
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT_PERCENT,
								new UnsignedDoublewordElement(40214)))); // P setpoint in %
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
					this.channel(PvInverterSMAHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (OpenemsNamedException e) {
					this.channel(PvInverterSMAHybrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
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
