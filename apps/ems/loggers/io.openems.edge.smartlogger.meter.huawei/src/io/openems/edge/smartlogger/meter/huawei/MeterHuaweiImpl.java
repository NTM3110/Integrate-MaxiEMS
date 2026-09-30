package io.openems.edge.smartlogger.meter.huawei;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT_IF_TRUE;

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
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "smartlogger.meter.huawei", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterHuaweiImpl extends AbstractOpenemsModbusComponent implements MeterHuawei, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public MeterHuaweiImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterHuawei.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		if(super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id())) {
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
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(32260, Priority.HIGH, //
					m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedDoublewordElement(32260).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_1), //
					m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedDoublewordElement(32262).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_1), //
					m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedDoublewordElement(32264).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_1), //
					m(MeterHuawei.ChannelId.AB_LINE_VOLTAGE, new UnsignedDoublewordElement(32266).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_1), //
					m(MeterHuawei.ChannelId.BC_LINE_VOLTAGE, new UnsignedDoublewordElement(32268).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_1), //
					m(MeterHuawei.ChannelId.CA_LINE_VOLTAGE, new UnsignedDoublewordElement(32270).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_1), //
					m(ElectricityMeter.ChannelId.CURRENT_L1, new SignedDoublewordElement(32272).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_2), //
					m(ElectricityMeter.ChannelId.CURRENT_L2, new SignedDoublewordElement(32274).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_2), //
					m(ElectricityMeter.ChannelId.CURRENT_L3, new SignedDoublewordElement(32276).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_2), //
					m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(32278).wordOrder(WordOrder.MSWLSW),
							INVERT_IF_TRUE(this.config.invert())), //
					m(ElectricityMeter.ChannelId.REACTIVE_POWER, new SignedDoublewordElement(32280).wordOrder(WordOrder.MSWLSW),
							INVERT_IF_TRUE(this.config.invert())), //
					m(MeterHuawei.ChannelId.ACTIVE_ELECTRICITY, new SignedDoublewordElement(32282).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
					m(MeterHuawei.ChannelId.POWER_FACTOR, new SignedWordElement(32284)), //
					m(MeterHuawei.ChannelId.REACTIVE_ELECTRICITY, new SignedDoublewordElement(32285).wordOrder(WordOrder.MSWLSW),
							ElementToChannelConverter.SCALE_FACTOR_MINUS_2_AND_INVERT_IF_TRUE(this.config.invert())), //
					m(ElectricityMeter.ChannelId.APPARENT_POWER, new SignedDoublewordElement(32287).wordOrder(WordOrder.MSWLSW),
							INVERT_IF_TRUE(this.config.invert())) //

				));
	}
	
	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}

	@Override
	public String debugLog() {
		return "Hello World";
	}
}
