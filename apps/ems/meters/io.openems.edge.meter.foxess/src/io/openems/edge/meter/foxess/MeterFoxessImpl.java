package io.openems.edge.meter.foxess;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT_IF_TRUE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Logger.Foxess", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class MeterFoxessImpl extends AbstractOpenemsModbusComponent //
		implements MeterFoxess, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public Config config;

	public MeterFoxessImpl() {
		super(OpenemsComponent.ChannelId.values(),//
				ModbusComponent.ChannelId.values(),//
				ElectricityMeter.ChannelId.values(),//
				MeterFoxess.ChannelId.values()//
		);
	}

	@Activate
	@Modified
	protected void activate(final ComponentContext context, final Config config) throws OpenemsException {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id());
	}

	@Deactivate
	@Override
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(40331, Priority.HIGH, //
					m(ElectricityMeter.ChannelId.ACTIVE_POWER,
							new SignedDoublewordElement(40331), INVERT_IF_TRUE(this.config.invertPower())),
					m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1,
							new SignedDoublewordElement(40333), INVERT_IF_TRUE(this.config.invertPower())),
					m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2,
							new SignedDoublewordElement(40335), INVERT_IF_TRUE(this.config.invertPower())),
					m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3,
							new SignedDoublewordElement(40337), INVERT_IF_TRUE(this.config.invertPower()))
				));
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}
}
