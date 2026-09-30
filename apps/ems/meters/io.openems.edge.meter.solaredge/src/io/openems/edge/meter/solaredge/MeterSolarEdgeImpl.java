package io.openems.edge.meter.solaredge;

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
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import java.util.function.BiConsumer;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.SolarEdge.Modbus", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterSolarEdgeImpl extends AbstractOpenemsModbusComponent implements MeterSolarEdge, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	private Config config;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public MeterSolarEdgeImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterSolarEdge.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		if(super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
		this.activateScaleFactorLogic();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {

		int b = this.config.baseAddress();

		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(b + 69, Priority.HIGH, //
					m(MeterSolarEdge.ChannelId.RAW_CURRENT, new SignedWordElement(b + 69)), //
					m(MeterSolarEdge.ChannelId.RAW_CURRENT_L1, new SignedWordElement(b + 70)), //
					m(MeterSolarEdge.ChannelId.RAW_CURRENT_L2, new SignedWordElement(b + 71)), //
					m(MeterSolarEdge.ChannelId.RAW_CURRENT_L3, new SignedWordElement(b + 72)), //
					m(MeterSolarEdge.ChannelId.CURRENT_SF, new SignedWordElement(b + 73)), // 
					new DummyRegisterElement(b + 74, b + 74),
					m(MeterSolarEdge.ChannelId.RAW_VOLTAGE_L1, new SignedWordElement(b + 75)), //
					m(MeterSolarEdge.ChannelId.RAW_VOLTAGE_L2, new SignedWordElement(b + 76)), //
					m(MeterSolarEdge.ChannelId.RAW_VOLTAGE_L3, new SignedWordElement(b + 77)), //
					
					new DummyRegisterElement(b + 78, b + 81),
					
					m(MeterSolarEdge.ChannelId.VOLTAGE_SF, new SignedWordElement(b + 82)), //
					m(MeterSolarEdge.ChannelId.RAW_FREQUENCY, new SignedWordElement(b + 83)), //
					m(MeterSolarEdge.ChannelId.FREQUENCY_SF, new SignedWordElement(b + 84)), //
					
					m(MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER, new SignedWordElement(b + 85)), //
					m(MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER_L1, new SignedWordElement(b + 86)), //
					m(MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER_L2, new SignedWordElement(b + 87)), //
					m(MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER_L3, new SignedWordElement(b + 88)), //
					m(MeterSolarEdge.ChannelId.ACTIVE_POWER_SF, new SignedWordElement(b + 89)), //
					m(MeterSolarEdge.ChannelId.RAW_APPARENT_POWER, new SignedWordElement(b + 90)), //
					m(MeterSolarEdge.ChannelId.RAW_APPARENT_POWER_L1, new SignedWordElement(b + 91)), //
					m(MeterSolarEdge.ChannelId.RAW_APPARENT_POWER_L2, new SignedWordElement(b + 92)), //
					m(MeterSolarEdge.ChannelId.RAW_APPARENT_POWER_L3, new SignedWordElement(b + 93)), //
					m(MeterSolarEdge.ChannelId.APPARENT_POWER_SF, new SignedWordElement(b + 94)),
					m(MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER, new SignedWordElement(b + 95)), //
					m(MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER_L1, new SignedWordElement(b + 96)), //
					m(MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER_L2, new SignedWordElement(b + 97)), //
					m(MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER_L3, new SignedWordElement(b + 98)), //
					m(MeterSolarEdge.ChannelId.REACTIVE_POWER_SF, new SignedWordElement(b + 99)),
					m(MeterSolarEdge.ChannelId.RAW_FACTOR_POWER, new SignedWordElement(b + 100)), //
					m(MeterSolarEdge.ChannelId.RAW_FACTOR_POWER_L1, new SignedWordElement(b + 101)), //
					m(MeterSolarEdge.ChannelId.RAW_FACTOR_POWER_L2, new SignedWordElement(b + 102)), //
					m(MeterSolarEdge.ChannelId.RAW_FACTOR_POWER_L3, new SignedWordElement(b + 103)), //
					m(MeterSolarEdge.ChannelId.FACTOR_POWER_SF, new SignedWordElement(b + 104))
				),
				
				new FC3ReadRegistersTask(b + 105, Priority.LOW, //
					m(MeterSolarEdge.ChannelId.RAW_EXPORTED_ENERGY, new UnsignedDoublewordElement(b + 105).wordOrder(WordOrder.MSWLSW)),
					new DummyRegisterElement(b + 107, b + 112),
					m(MeterSolarEdge.ChannelId.RAW_IMPORTED_ENERGY, new UnsignedDoublewordElement(b + 113).wordOrder(WordOrder.MSWLSW)),
					new DummyRegisterElement(b + 115, b + 120),
					m(MeterSolarEdge.ChannelId.EXPORTED_ENERGY_SF, new SignedWordElement(b + 121))//
				)
		);
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	private void activateScaleFactorLogic() {
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT, MeterSolarEdge.ChannelId.RAW_CURRENT, MeterSolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT_L1, MeterSolarEdge.ChannelId.RAW_CURRENT_L1, MeterSolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT_L2, MeterSolarEdge.ChannelId.RAW_CURRENT_L2, MeterSolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT_L3, MeterSolarEdge.ChannelId.RAW_CURRENT_L3, MeterSolarEdge.ChannelId.CURRENT_SF, 3);
		
		this.addScaleFactorListener(ElectricityMeter.ChannelId.VOLTAGE_L1, MeterSolarEdge.ChannelId.RAW_VOLTAGE_L1, MeterSolarEdge.ChannelId.VOLTAGE_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.VOLTAGE_L2, MeterSolarEdge.ChannelId.RAW_VOLTAGE_L2, MeterSolarEdge.ChannelId.VOLTAGE_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.VOLTAGE_L3, MeterSolarEdge.ChannelId.RAW_VOLTAGE_L3, MeterSolarEdge.ChannelId.VOLTAGE_SF, 3);
		
		this.addScaleFactorListener(ElectricityMeter.ChannelId.FREQUENCY, MeterSolarEdge.ChannelId.RAW_FREQUENCY, MeterSolarEdge.ChannelId.FREQUENCY_SF, 3);

		// Power
		this.addScaleFactorListener(ElectricityMeter.ChannelId.ACTIVE_POWER, MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER, MeterSolarEdge.ChannelId.ACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER_L1, MeterSolarEdge.ChannelId.ACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER_L2, MeterSolarEdge.ChannelId.ACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, MeterSolarEdge.ChannelId.RAW_ACTIVE_POWER_L3, MeterSolarEdge.ChannelId.ACTIVE_POWER_SF, 0);

		// Apparent Power
		this.addScaleFactorListener(ElectricityMeter.ChannelId.APPARENT_POWER, MeterSolarEdge.ChannelId.RAW_APPARENT_POWER, MeterSolarEdge.ChannelId.APPARENT_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.APPARENT_POWER_L1, MeterSolarEdge.ChannelId.RAW_APPARENT_POWER_L1, MeterSolarEdge.ChannelId.APPARENT_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.APPARENT_POWER_L2, MeterSolarEdge.ChannelId.RAW_APPARENT_POWER_L2, MeterSolarEdge.ChannelId.APPARENT_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.APPARENT_POWER_L3, MeterSolarEdge.ChannelId.RAW_APPARENT_POWER_L3, MeterSolarEdge.ChannelId.APPARENT_POWER_SF, 0);

		// Reactive Power
		this.addScaleFactorListener(ElectricityMeter.ChannelId.REACTIVE_POWER, MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER, MeterSolarEdge.ChannelId.REACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER_L1, MeterSolarEdge.ChannelId.REACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER_L2, MeterSolarEdge.ChannelId.REACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, MeterSolarEdge.ChannelId.RAW_REACTIVE_POWER_L3, MeterSolarEdge.ChannelId.REACTIVE_POWER_SF, 0);
		
		// Energy
		this.addEnergyScaleFactorListener(MeterSolarEdge.ChannelId.EXPORTED_ENERGY, MeterSolarEdge.ChannelId.RAW_EXPORTED_ENERGY, MeterSolarEdge.ChannelId.EXPORTED_ENERGY_SF, 0);
		this.addEnergyScaleFactorListener(MeterSolarEdge.ChannelId.IMPORTED_ENERGY, MeterSolarEdge.ChannelId.RAW_IMPORTED_ENERGY, MeterSolarEdge.ChannelId.EXPORTED_ENERGY_SF, 0);
	}

	private void addScaleFactorListener(io.openems.edge.common.channel.ChannelId targetId,
			io.openems.edge.common.channel.ChannelId rawId, io.openems.edge.common.channel.ChannelId sfId,
			int unitConversionPower) {
		final BiConsumer<Value<Integer>, Value<Integer>> callback = (oldValue, newValue) -> {
			Channel<Integer> rawChannel = this.channel(rawId);
			Channel<Integer> sfChannel = this.channel(sfId);
			Channel<Object> targetChannel = this.channel(targetId);

			Value<Integer> rawValue = rawChannel.value();
			Value<Integer> sfValue = sfChannel.value();

			if (!rawValue.isDefined() || !sfValue.isDefined()) {
				return;
			}

			double value = rawValue.get().doubleValue() * Math.pow(10, sfValue.get() + unitConversionPower);

			// Invert if configured (only for Power channels)
			if (this.config.invert() && (
					targetId == ElectricityMeter.ChannelId.ACTIVE_POWER ||
					targetId == ElectricityMeter.ChannelId.REACTIVE_POWER ||
					targetId == ElectricityMeter.ChannelId.APPARENT_POWER ||
					targetId == ElectricityMeter.ChannelId.ACTIVE_POWER_L1 ||
					targetId == ElectricityMeter.ChannelId.ACTIVE_POWER_L2 ||
					targetId == ElectricityMeter.ChannelId.ACTIVE_POWER_L3 ||
					targetId == ElectricityMeter.ChannelId.REACTIVE_POWER_L1 ||
					targetId == ElectricityMeter.ChannelId.REACTIVE_POWER_L2 ||
					targetId == ElectricityMeter.ChannelId.REACTIVE_POWER_L3 ||
					targetId == ElectricityMeter.ChannelId.APPARENT_POWER_L1 ||
					targetId == ElectricityMeter.ChannelId.APPARENT_POWER_L2 ||
					targetId == ElectricityMeter.ChannelId.APPARENT_POWER_L3
					)) {
				value *= -1;
			}

			switch (targetChannel.channelId().doc().getType()) {
			case INTEGER:
				targetChannel.setNextValue((int) value);
				break;
			case LONG:
				targetChannel.setNextValue((long) value);
				break;
			default:
				targetChannel.setNextValue(value);
				break;
			}
		};
		
		Channel<Integer> rawChannel = this.channel(rawId);
		Channel<Integer> sfChannel = this.channel(sfId);
		
		rawChannel.onChange(callback);
		sfChannel.onChange(callback);
	}

	private void addEnergyScaleFactorListener(io.openems.edge.common.channel.ChannelId targetId,
			io.openems.edge.common.channel.ChannelId rawId, io.openems.edge.common.channel.ChannelId sfId,
			int unitConversionPower) {
		
		Runnable updateLogic = () -> {
			Channel<Long> rawChannel = this.channel(rawId);
			Channel<Integer> sfChannel = this.channel(sfId);
			Channel<Object> targetChannel = this.channel(targetId);

			Value<Long> rawValue = rawChannel.value();
			Value<Integer> sfValue = sfChannel.value();

			if (!rawValue.isDefined() || !sfValue.isDefined()) {
				return;
			}

			double value = rawValue.get().doubleValue() * Math.pow(10, sfValue.get() + unitConversionPower);

			switch (targetChannel.channelId().doc().getType()) {
			case LONG:
				targetChannel.setNextValue((long) value);
				break;
			case INTEGER:
				targetChannel.setNextValue((int) value);
				break;
			default:
				targetChannel.setNextValue(value);
				break;
			}
		};

		final BiConsumer<Value<Long>, Value<Long>> rawCallback = (oldValue, newValue) -> {
			updateLogic.run();
		};

		final BiConsumer<Value<Integer>, Value<Integer>> sfCallback = (oldValue, newValue) -> {
			updateLogic.run();
		};
		
		Channel<Long> rawChannel = this.channel(rawId);
		Channel<Integer> sfChannel = this.channel(sfId);
		
		rawChannel.onChange(rawCallback);
		sfChannel.onChange(sfCallback);
	}
}
