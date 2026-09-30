package io.openems.edge.meter.sungrow.acrel;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;

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
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "io.openems.edge.meter.sungrow.acrel", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class ArtelDTDS1352Impl extends AbstractOpenemsModbusComponent
		implements ArtelDTDS1352, ModbusComponent, OpenemsComponent, EventHandler {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public ArtelDTDS1352Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ArtelDTDS1352.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.calculateScaledValues();
			break;
		}
	}

	@SuppressWarnings("unchecked")
	private void calculateScaledValues() {
		// Get PT and CT values (default to 1 if not defined or 0)
		int pt = 1;
		if (this.channel(ArtelDTDS1352.ChannelId.P_T).value().isDefined()) {
			pt = (int) this.channel(ArtelDTDS1352.ChannelId.P_T).value().get();
		}
		if (pt == 0) {
			pt = 1;
		}

		int ct = 1;
		if (this.channel(ArtelDTDS1352.ChannelId.C_T).value().isDefined()) {
			ct = (int) this.channel(ArtelDTDS1352.ChannelId.C_T).value().get();
		}
		if (ct == 0) {
			ct = 1;
		}


		// Determine Invert Sign
		int invert = this.config.invert() ? -1 : 1;

		// Voltage Calculation: data * PT * 0.1 (V) -> * 100 (mV)
		this.calculateAndSetPower(ElectricityMeter.ChannelId.VOLTAGE_L1, ArtelDTDS1352.ChannelId.RAW_VOLTAGE_L1, pt, 1, 100);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.VOLTAGE_L2, ArtelDTDS1352.ChannelId.RAW_VOLTAGE_L2, pt, 1, 100);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.VOLTAGE_L3, ArtelDTDS1352.ChannelId.RAW_VOLTAGE_L3, pt, 1, 100);

		// Current Calculation: data * CT * 0.01 (A) -> * 10 (mA)
		this.calculateAndSetPower(ElectricityMeter.ChannelId.CURRENT_L1, ArtelDTDS1352.ChannelId.RAW_CURRENT_L1, 1, ct, 10);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.CURRENT_L2, ArtelDTDS1352.ChannelId.RAW_CURRENT_L2, 1, ct, 10);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.CURRENT_L3, ArtelDTDS1352.ChannelId.RAW_CURRENT_L3, 1, ct, 10);

		// Power Calculation: data * PT * CT (W/Var/VA) -> * 1 * invert
		this.calculateAndSetPower(ElectricityMeter.ChannelId.ACTIVE_POWER, ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER, pt, ct, 1 * invert);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER_L1, pt, ct, 1 * invert);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER_L2, pt, ct, 1 * invert);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER_L3, pt, ct, 1 * invert);

		this.calculateAndSetPower(ElectricityMeter.ChannelId.REACTIVE_POWER, ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER, pt, ct, 1 * invert);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER_L1, pt, ct, 1 * invert);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER_L2, pt, ct, 1 * invert);
		this.calculateAndSetPower(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER_L3, pt, ct, 1 * invert);
	}

	@SuppressWarnings("unchecked")
	private void calculateAndSetPower(io.openems.edge.common.channel.ChannelId targetId,
			io.openems.edge.common.channel.ChannelId sourceId, int pt, int ct, int multiplier) {
		if (this.channel(sourceId).value().isDefined()) {
			Integer raw = (Integer) this.channel(sourceId).value().get();
			long calculated = (long) raw * pt * ct * multiplier;
			// Detect overflow for Integer channels of ElectricityMeter
			if (calculated > Integer.MAX_VALUE) {
				this.channel(targetId).setNextValue(Integer.MAX_VALUE);
			} else if (calculated < Integer.MIN_VALUE) {
				this.channel(targetId).setNextValue(Integer.MIN_VALUE);
			} else {
				this.channel(targetId).setNextValue((int) calculated);
			}
		} else {
			this.channel(targetId).setNextValue(null);
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x0061, Priority.HIGH, //
						m(ArtelDTDS1352.ChannelId.RAW_VOLTAGE_L1, new SignedWordElement(0x0061)), //
						m(ArtelDTDS1352.ChannelId.RAW_VOLTAGE_L2, new SignedWordElement(0x0062)), //
						m(ArtelDTDS1352.ChannelId.RAW_VOLTAGE_L3, new SignedWordElement(0x0063)), //
						m(ArtelDTDS1352.ChannelId.RAW_CURRENT_L1, new SignedWordElement(0x0064)), //
						m(ArtelDTDS1352.ChannelId.RAW_CURRENT_L2, new SignedWordElement(0x0065)), //
						m(ArtelDTDS1352.ChannelId.RAW_CURRENT_L3, new SignedWordElement(0x0066)), //
						new DummyRegisterElement(0x0067, 0x0076), //
						
						m(ElectricityMeter.ChannelId.FREQUENCY, new SignedWordElement(0x0077), SCALE_FACTOR_1) //
				), //
				new FC3ReadRegistersTask(0x008D, Priority.LOW, //
						m(ArtelDTDS1352.ChannelId.P_T, new SignedWordElement(0x008D)), //
						m(ArtelDTDS1352.ChannelId.C_T, new SignedWordElement(0x008E)) //
				), //
				new FC3ReadRegistersTask(0x0164, Priority.HIGH, //
						m(ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER_L1, new SignedDoublewordElement(0x0164)), //
						m(ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER_L2, new SignedDoublewordElement(0x0166)), //
						m(ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER_L3, new SignedDoublewordElement(0x0168)), //
						m(ArtelDTDS1352.ChannelId.RAW_ACTIVE_POWER, new SignedDoublewordElement(0x016A)), //
						m(ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER_L1, new SignedDoublewordElement(0x016C)), //
						m(ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER_L2, new SignedDoublewordElement(0x016E)), //
						m(ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER_L3, new SignedDoublewordElement(0x0170)), //
						m(ArtelDTDS1352.ChannelId.RAW_REACTIVE_POWER, new SignedDoublewordElement(0x0172)), //
						m(ArtelDTDS1352.ChannelId.RAW_APPARENT_POWER_L1, new SignedDoublewordElement(0x0174)), //
						m(ArtelDTDS1352.ChannelId.RAW_APPARENT_POWER_L2, new SignedDoublewordElement(0x0176)), //
						m(ArtelDTDS1352.ChannelId.RAW_APPARENT_POWER_L3, new SignedDoublewordElement(0x0178)), //
						m(ArtelDTDS1352.ChannelId.RAW_APPARENT_POWER, new SignedDoublewordElement(0x017A)), //
						m(ArtelDTDS1352.ChannelId.POWER_FACTOR_L1, new SignedWordElement(0x017C)), //
						m(ArtelDTDS1352.ChannelId.POWER_FACTOR_L2, new SignedWordElement(0x017D)), //
						m(ArtelDTDS1352.ChannelId.POWER_FACTOR_L3, new SignedWordElement(0x017E)), //
						m(ArtelDTDS1352.ChannelId.POWER_FACTOR, new SignedWordElement(0x017F)) //
				));
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}
}
