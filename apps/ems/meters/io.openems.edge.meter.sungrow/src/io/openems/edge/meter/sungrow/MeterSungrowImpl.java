package io.openems.edge.meter.sungrow;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;

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
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "meter.sungrow", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterSungrowImpl extends AbstractOpenemsModbusComponent
		implements MeterSungrow, ElectricityMeter, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public MeterSungrowImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterSungrow.ChannelId.values() //
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
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC4ReadInputRegistersTask(5083, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(5083)), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new SignedDoublewordElement(5085)), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new SignedDoublewordElement(5087)), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new SignedDoublewordElement(5089)), //
						m(MeterSungrow.ChannelId.LOAD_POWER, new SignedDoublewordElement(5091)), //
						m(MeterSungrow.ChannelId.DAILY_EXPORT_ENERGY, new UnsignedDoublewordElement(5093),
								SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, new UnsignedDoublewordElement(5095),
								SCALE_FACTOR_2), //
						m(MeterSungrow.ChannelId.DAILY_IMPORT_ENERGY, new UnsignedDoublewordElement(5097),
								SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, new UnsignedDoublewordElement(5099),
								SCALE_FACTOR_2), //
						m(MeterSungrow.ChannelId.DAILY_DIRECT_ENERGY_CONSUMPTION, new UnsignedDoublewordElement(5101),
								SCALE_FACTOR_2), //
						m(MeterSungrow.ChannelId.TOTAL_DIRECT_ENERGY_CONSUMPTION, new UnsignedDoublewordElement(5103),
								SCALE_FACTOR_2) //
				));
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}
}
