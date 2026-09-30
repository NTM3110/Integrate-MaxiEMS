package io.openems.edge.pvinverter.solis.scada.ongrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;

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
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
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
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PV-Inverter.Solis.SCADA.OnGrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterSolisOnGridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSolisOnGrid, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent,
		OpenemsComponent,
		EventHandler, ModbusSlave, TimedataProvider {

	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);
	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

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

	public PvInverterSolisOnGridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterSolisOnGrid.ChannelId.values() //
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
				new FC4ReadInputRegistersTask(2999, Priority.LOW,
						m(PvInverterSolisOnGrid.ChannelId.DEVICE_MODEL, new UnsignedWordElement(2999)),
						m(PvInverterSolisOnGrid.ChannelId.DEVICE_FIRMWARE_VERSION, new UnsignedWordElement(3000))),
				// =====================================================================
				// PV Input Section
				// =====================================================================
				new FC4ReadInputRegistersTask(3004, Priority.LOW,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new UnsignedDoublewordElement(3004).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisOnGrid.ChannelId.DC_TOTAL,
								new UnsignedDoublewordElement(3006).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisOnGrid.ChannelId.ENERGY_PRODUCTION_TOTAL,
								new UnsignedDoublewordElement(3008).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisOnGrid.ChannelId.ENERGY_PRODUCTION_MONTHLY,
								new UnsignedDoublewordElement(3010).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisOnGrid.ChannelId.ENERGY_PRODUCTION_LASTMONTHLY,
								new UnsignedDoublewordElement(3012).wordOrder(WordOrder.MSWLSW)),
						m(PvInverterSolisOnGrid.ChannelId.ENERGY_PRODUCTION_TODAY,
								new UnsignedWordElement(3014), SCALE_FACTOR_MINUS_1),
						m(PvInverterSolisOnGrid.ChannelId.ENERGY_PRODUCTION_YESTERDAY,
								new UnsignedWordElement(3015), SCALE_FACTOR_MINUS_1)
				),

				new FC4ReadInputRegistersTask(3021, Priority.HIGH,
						m(PvInverterSolisOnGrid.ChannelId.DC1_VOLTAGE,
								new UnsignedWordElement(3021), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC1_CURRENT,
								new UnsignedWordElement(3022), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC2_VOLTAGE,
								new UnsignedWordElement(3023), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC2_CURRENT,
								new UnsignedWordElement(3024), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC3_VOLTAGE,
								new UnsignedWordElement(3025), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC3_CURRENT,
								new UnsignedWordElement(3026), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC4_VOLTAGE,
								new UnsignedWordElement(3027), SCALE_FACTOR_2),
						m(PvInverterSolisOnGrid.ChannelId.DC4_CURRENT,
								new UnsignedWordElement(3028), SCALE_FACTOR_2),
						new DummyRegisterElement(3029, 3030),
						m(PvInverterSolisOnGrid.ChannelId.DC_BUS_VOLTAGE,
								new UnsignedWordElement(3031), SCALE_FACTOR_3),
						new DummyRegisterElement(3032, 3032),

						// =================================================================
						// AC Side (Grid / Output)
						// =================================================================
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(3033), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(3034), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(3035), getScaleConverter(2)),
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedWordElement(3036), getScaleConverter(2)),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedWordElement(3037), getScaleConverter(2)),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedWordElement(3038), getScaleConverter(2)),
						new DummyRegisterElement(3039, 3040),
						m(PvInverterSolisOnGrid.ChannelId.TEMPERATURE,
								new UnsignedWordElement(3041), SCALE_FACTOR_MINUS_1),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedWordElement(3042), SCALE_FACTOR_1),
						new DummyRegisterElement(3043, 3049),
						m(PvInverterSolisOnGrid.ChannelId.PF,
								new SignedWordElement(3050), SCALE_FACTOR_MINUS_3),
						new DummyRegisterElement(3051, 3054),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(3055).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new SignedDoublewordElement(3057).wordOrder(WordOrder.MSWLSW))),
				new FC4ReadInputRegistersTask(3060, Priority.LOW,
						m(PvInverterSolisOnGrid.ChannelId.DEVICE_SERIAL_NUMBER, new StringWordElement(3060, 4)),
						new DummyRegisterElement(3064, 3070),
						m(PvInverterSolisOnGrid.ChannelId.MODES, new UnsignedWordElement(3071))),
				new FC4ReadInputRegistersTask(3081, Priority.HIGH,
						m(PvInverterSolisOnGrid.ChannelId.METER_VOLTAGE_L1,
								new UnsignedWordElement(3081), SCALE_FACTOR_3),
						m(PvInverterSolisOnGrid.ChannelId.METER_CURRENT_L1,
								new UnsignedWordElement(3082), SCALE_FACTOR_3),
						m(PvInverterSolisOnGrid.ChannelId.METER_ACTIVE_POWER_TOTAL,
								new SignedDoublewordElement(3083).wordOrder(WordOrder.MSWLSW))),
				new FC4ReadInputRegistersTask(35000, Priority.LOW,
						m(PvInverterSolisOnGrid.ChannelId.DEVICE_PROTOCOL_VERSION, new UnsignedWordElement(35000))),
				new FC6WriteRegisterTask(3069, //
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new UnsignedWordElement(3069))),
				new FC6WriteRegisterTask(3070, //
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q, new UnsignedWordElement(3070))),
				new FC16WriteRegistersTask(3080,
						m(PvInverterSolisOnGrid.ChannelId.P_LIMIT, new SignedWordElement(3080)),  // Scaled: 1 unit = 10W
						new DummyRegisterElement(3081, 3081),
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT, new SignedWordElement(3082))));
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

					this.channel(PvInverterSolisOnGrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (OpenemsNamedException e) {
					this.channel(PvInverterSolisOnGrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
				}
				break;
			case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
				Integer activePower = this.getActivePower().get();
				this.calculateProductionEnergy.update(activePower);
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
	private ElementToChannelConverter getScaleConverter(int scaleExponent) {
		switch (scaleExponent) {
		case 1: return SCALE_FACTOR_1;
		case 2: return SCALE_FACTOR_2;
		case 3: return SCALE_FACTOR_3;
		case -1: return SCALE_FACTOR_MINUS_1;
		case -2: return SCALE_FACTOR_MINUS_2;
		case -3: return SCALE_FACTOR_MINUS_3;
		case 0: return DIRECT_1_TO_1;
		default:
			return new ElementToChannelConverter(value -> {
				if (value == null) return null;
				if (value instanceof Long) return (int) ((Long) value * Math.pow(10, scaleExponent));
				if (value instanceof Integer) return (int) ((Integer) value * Math.pow(10, scaleExponent));
				return value;
			});
		}
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
