package io.openems.edge.meter.sma;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT_IF_TRUE;
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
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.SMA", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterSmaImpl extends AbstractOpenemsModbusComponent
		implements MeterSma, ElectricityMeter, ModbusComponent, OpenemsComponent {

	private static final ElementToChannelConverter POWER_FACTOR = new ElementToChannelConverter(value -> {
		if (value == null) {
			return null;
		}
		return ((Number) value).floatValue() / 100F;
	});

	@Reference
	private ConfigurationAdmin cm;

	private MeterType meterType = MeterType.PRODUCTION;
	private boolean invert;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public MeterSmaImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterSma.ChannelId.values() //
		);

		// SMA does not provide an aggregate current register.
		ElectricityMeter.calculateSumCurrentFromPhases(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.meterType = config.type();
		this.invert = config.invert();

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
				// Energy counters [Wh]
				new FC3ReadRegistersTask(30581, Priority.LOW, //
						m(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY,
								new UnsignedDoublewordElement(30581)),
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(30583))),

				// Phase-to-neutral voltage [0.01 V -> mV] and phase active power [W]
				new FC3ReadRegistersTask(31253, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedDoublewordElement(31253),
								SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedDoublewordElement(31255),
								SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedDoublewordElement(31257),
								SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new UnsignedDoublewordElement(31259),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new UnsignedDoublewordElement(31261),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new UnsignedDoublewordElement(31263),
								INVERT_IF_TRUE(this.invert)),
						new DummyRegisterElement(31265, 31270),
						// Reactive power [VAr]
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, new SignedDoublewordElement(31271),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, new SignedDoublewordElement(31273),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, new SignedDoublewordElement(31275),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER, new SignedDoublewordElement(31277),
								INVERT_IF_TRUE(this.invert))),

				// Power factor [0.01], current [0.001 A -> mA], apparent power [VA]
				// and frequency [0.01 Hz -> mHz]
				new FC3ReadRegistersTask(31433, Priority.HIGH, //
						m(MeterSma.ChannelId.POWER_FACTOR, new UnsignedDoublewordElement(31433), POWER_FACTOR),
						m(ElectricityMeter.ChannelId.CURRENT_L1, new SignedDoublewordElement(31435)),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new SignedDoublewordElement(31437)),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new SignedDoublewordElement(31439)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L1, new SignedDoublewordElement(31441),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L2, new SignedDoublewordElement(31443),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L3, new SignedDoublewordElement(31445),
								INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedDoublewordElement(31447),
								SCALE_FACTOR_1),
						new DummyRegisterElement(31449, 31454),
						m(ElectricityMeter.ChannelId.APPARENT_POWER, new SignedDoublewordElement(31455),
								INVERT_IF_TRUE(this.invert))) //
		);
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}
}
