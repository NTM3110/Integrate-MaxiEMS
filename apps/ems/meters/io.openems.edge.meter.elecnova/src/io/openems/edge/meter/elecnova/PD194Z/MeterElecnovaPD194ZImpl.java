package io.openems.edge.meter.elecnova.PD194Z;

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
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;

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
		name = "Meter.Elecnova.PD194Z.E14", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
})
public class MeterElecnovaPD194ZImpl extends AbstractOpenemsModbusComponent
		implements MeterElecnovaPD194Z, ElectricityMeter, ModbusComponent, OpenemsComponent, TimedataProvider,
		EventHandler {

	private CalculateEnergyFromPower calculateProductionEnergy;
	private CalculateEnergyFromPower calculateConsumptionEnergy;

	private MeterType meterType = MeterType.PRODUCTION;

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

	public MeterElecnovaPD194ZImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterElecnovaPD194Z.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.meterType = config.type();

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
				// Voltages (V -> mV)
				new FC3ReadRegistersTask(0x0006, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new FloatDoublewordElement(0x0006), SCALE_FACTOR_3),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new FloatDoublewordElement(0x0008), SCALE_FACTOR_3),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new FloatDoublewordElement(0x000A), SCALE_FACTOR_3)),
				// Frequency (mHz - multiply by 1000)
				new FC3ReadRegistersTask(0x0012, Priority.LOW, //
						m(ElectricityMeter.ChannelId.FREQUENCY, new FloatDoublewordElement(0x0012), SCALE_FACTOR_3)),

				// ====== Data Line 1 ======
				// Current: A -> mA, Power: kW -> W, kVar -> Var, kVA -> VA
				new FC3ReadRegistersTask(0x0014, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L1_IA, new FloatDoublewordElement(0x0014), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_IB, new FloatDoublewordElement(0x0016), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_IC, new FloatDoublewordElement(0x0018), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_I, new FloatDoublewordElement(0x001A), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_PA, new FloatDoublewordElement(0x001C), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_PB, new FloatDoublewordElement(0x001E), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_PC, new FloatDoublewordElement(0x0020), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_P, new FloatDoublewordElement(0x0022), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_QA, new FloatDoublewordElement(0x0024), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_QB, new FloatDoublewordElement(0x0026), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_QC, new FloatDoublewordElement(0x0028), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_Q, new FloatDoublewordElement(0x002A), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_SA, new FloatDoublewordElement(0x002C), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_SB, new FloatDoublewordElement(0x002E), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_SC, new FloatDoublewordElement(0x0030), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_S, new FloatDoublewordElement(0x0032), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L1_PFA, new FloatDoublewordElement(0x0034)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PFB, new FloatDoublewordElement(0x0036)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PFC, new FloatDoublewordElement(0x0038)),
						m(MeterElecnovaPD194Z.ChannelId.L1_PF, new FloatDoublewordElement(0x003A))),

				// ====== Data Line 2 ======
				new FC3ReadRegistersTask(0x005C, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L2_IA, new FloatDoublewordElement(0x005C), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_IB, new FloatDoublewordElement(0x005E), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_IC, new FloatDoublewordElement(0x0060), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_I, new FloatDoublewordElement(0x0062), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_PA, new FloatDoublewordElement(0x0064), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_PB, new FloatDoublewordElement(0x0066), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_PC, new FloatDoublewordElement(0x0068), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_P, new FloatDoublewordElement(0x006A), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_QA, new FloatDoublewordElement(0x006C), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_QB, new FloatDoublewordElement(0x006E), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_QC, new FloatDoublewordElement(0x0070), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_Q, new FloatDoublewordElement(0x0072), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_SA, new FloatDoublewordElement(0x0074), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_SB, new FloatDoublewordElement(0x0076), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_SC, new FloatDoublewordElement(0x0078), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_S, new FloatDoublewordElement(0x007A), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L2_PFA, new FloatDoublewordElement(0x007C)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PFB, new FloatDoublewordElement(0x007E)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PFC, new FloatDoublewordElement(0x0080)),
						m(MeterElecnovaPD194Z.ChannelId.L2_PF, new FloatDoublewordElement(0x0082))),

				// ====== Data Line 3 ======
				new FC3ReadRegistersTask(0x00A4, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L3_IA, new FloatDoublewordElement(0x00A4), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_IB, new FloatDoublewordElement(0x00A6), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_IC, new FloatDoublewordElement(0x00A8), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_I, new FloatDoublewordElement(0x00AA), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_PA, new FloatDoublewordElement(0x00AC), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_PB, new FloatDoublewordElement(0x00AE), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_PC, new FloatDoublewordElement(0x00B0), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_P, new FloatDoublewordElement(0x00B2), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_QA, new FloatDoublewordElement(0x00B4), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_QB, new FloatDoublewordElement(0x00B6), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_QC, new FloatDoublewordElement(0x00B8), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_Q, new FloatDoublewordElement(0x00BA), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_SA, new FloatDoublewordElement(0x00BC), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_SB, new FloatDoublewordElement(0x00BE), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_SC, new FloatDoublewordElement(0x00C0), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_S, new FloatDoublewordElement(0x00C2), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L3_PFA, new FloatDoublewordElement(0x00C4)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PFB, new FloatDoublewordElement(0x00C6)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PFC, new FloatDoublewordElement(0x00C8)),
						m(MeterElecnovaPD194Z.ChannelId.L3_PF, new FloatDoublewordElement(0x00CA))),

				// ====== Data Line 4 ======
				new FC3ReadRegistersTask(0x00EC, Priority.HIGH, //
						m(MeterElecnovaPD194Z.ChannelId.L4_IA, new FloatDoublewordElement(0x00EC), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_IB, new FloatDoublewordElement(0x00EE), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_IC, new FloatDoublewordElement(0x00F0), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_I, new FloatDoublewordElement(0x00F2), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_PA, new FloatDoublewordElement(0x00F4), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_PB, new FloatDoublewordElement(0x00F6), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_PC, new FloatDoublewordElement(0x00F8), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_P, new FloatDoublewordElement(0x00FA), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_QA, new FloatDoublewordElement(0x00FC), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_QB, new FloatDoublewordElement(0x00FE), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_QC, new FloatDoublewordElement(0x0100), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_Q, new FloatDoublewordElement(0x0102), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_SA, new FloatDoublewordElement(0x0104), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_SB, new FloatDoublewordElement(0x0106), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_SC, new FloatDoublewordElement(0x0108), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_S, new FloatDoublewordElement(0x010A), SCALE_FACTOR_3),
						m(MeterElecnovaPD194Z.ChannelId.L4_PFA, new FloatDoublewordElement(0x010C)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PFB, new FloatDoublewordElement(0x010E)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PFC, new FloatDoublewordElement(0x0110)),
						m(MeterElecnovaPD194Z.ChannelId.L4_PF, new FloatDoublewordElement(0x0112))));
	}

	/**
	 * Calculate the Energy values from ActivePower.
	 */
	private void calculateEnergy() {
		// Calculate Energy - values are already in W after MULTIPLY(1000) conversion
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
