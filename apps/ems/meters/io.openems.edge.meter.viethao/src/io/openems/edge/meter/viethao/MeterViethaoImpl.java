package io.openems.edge.meter.viethao;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1_AND_INVERT_IF_TRUE;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2_AND_INVERT_IF_TRUE;

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
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Viethao", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterViethaoImpl extends AbstractOpenemsModbusComponent
		implements MeterViethao, ElectricityMeter, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	private MeterType meterType = MeterType.PRODUCTION;
	private boolean invert;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public MeterViethaoImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values() //
		);

		// The protocol provides current for each phase, but no aggregate current.
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
				// Viethao EMS protocol: registers 0..17, all U16 and read-only.
				// Convert 0.1 V to mV (x100), 0.01 A to mA (x10), and 0.01 kW/kvar/kVA
				// to W/var/VA (x10).
				new FC3ReadRegistersTask(0, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(0), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(1), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(2), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(3), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(4), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(5), SCALE_FACTOR_1),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new UnsignedWordElement(6),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new UnsignedWordElement(7),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new UnsignedWordElement(8),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new UnsignedWordElement(9),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, new UnsignedWordElement(10),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, new UnsignedWordElement(11),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, new UnsignedWordElement(12),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER, new UnsignedWordElement(13),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L1, new UnsignedWordElement(14),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L2, new UnsignedWordElement(15),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER_L3, new UnsignedWordElement(16),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER, new UnsignedWordElement(17),
								SCALE_FACTOR_2_AND_INVERT_IF_TRUE(this.invert))) //
		);
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}
}
