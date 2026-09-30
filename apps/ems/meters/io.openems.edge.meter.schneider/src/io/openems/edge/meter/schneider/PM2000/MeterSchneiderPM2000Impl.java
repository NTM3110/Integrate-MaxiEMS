package io.openems.edge.meter.schneider.PM2000;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3_AND_INVERT_IF_TRUE;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;

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

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Schneider.PM2000Series", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class MeterSchneiderPM2000Impl extends AbstractOpenemsModbusComponent
		implements MeterSchneiderPM2000, ElectricityMeter,
		ModbusComponent, OpenemsComponent, TimedataProvider, EventHandler {

	private CalculateEnergyFromPower calculateProductionEnergy;
	private CalculateEnergyFromPower calculateConsumptionEnergy;

	private MeterType meterType = MeterType.PRODUCTION;
	private boolean invert;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public MeterSchneiderPM2000Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterSchneiderPM2000.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.meterType = config.type();
		this.invert = config.invert();

		this.calculateProductionEnergy = new CalculateEnergyFromPower(this,
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, this.componentManager.getClock());
		this.calculateConsumptionEnergy = new CalculateEnergyFromPower(this,
				ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, this.componentManager.getClock());

		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public MeterType getMeterType() {
		return this.meterType;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				// Currents (A -> mA)
				new FC3ReadRegistersTask(3000, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.CURRENT_L1, new FloatDoublewordElement(3000), SCALE_FACTOR_3),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new FloatDoublewordElement(3002), SCALE_FACTOR_3),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new FloatDoublewordElement(3004), SCALE_FACTOR_3),
						new DummyRegisterElement(3006, 3009),
						m(ElectricityMeter.ChannelId.CURRENT, new FloatDoublewordElement(3010), SCALE_FACTOR_3)),

				// Voltages (V -> mV)
				new FC3ReadRegistersTask(3028, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new FloatDoublewordElement(3028), SCALE_FACTOR_3),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new FloatDoublewordElement(3030), SCALE_FACTOR_3),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new FloatDoublewordElement(3032), SCALE_FACTOR_3),
						new DummyRegisterElement(3034, 3035),
						m(ElectricityMeter.ChannelId.VOLTAGE, new FloatDoublewordElement(3036), SCALE_FACTOR_3)),
				// Power values (kW -> W, kVar -> Var, kVA -> VA)
				new FC3ReadRegistersTask(3054, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new FloatDoublewordElement(3054),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new FloatDoublewordElement(3056),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new FloatDoublewordElement(3058),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new FloatDoublewordElement(3060),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, new FloatDoublewordElement(3062),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, new FloatDoublewordElement(3064),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, new FloatDoublewordElement(3066),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER, new FloatDoublewordElement(3068),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L1, new FloatDoublewordElement(3070),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L2, new FloatDoublewordElement(3072),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L3, new FloatDoublewordElement(3074),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER, new FloatDoublewordElement(3076),
								SCALE_FACTOR_3_AND_INVERT_IF_TRUE(this.invert))),
				// Frequency (Hz -> mHz)
				new FC3ReadRegistersTask(3110, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.FREQUENCY, new FloatDoublewordElement(3110), SCALE_FACTOR_3))
		//
		);

	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		// Values are already in W after SCALE_FACTOR_3 conversion
		final Integer activePower = this.getActivePower().get();
		if (activePower == null) {
			this.calculateProductionEnergy.update(null);
			this.calculateConsumptionEnergy.update(null);
		} else if (activePower >= 0) {
			this.calculateProductionEnergy.update(activePower);
			this.calculateConsumptionEnergy.update(0);
		} else {
			this.calculateProductionEnergy.update(0);
			this.calculateConsumptionEnergy.update(-activePower);
		}
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
			case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.calculateEnergy();
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
