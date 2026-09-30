package io.openems.edge.logger.huawei;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.logger.api.SmartLogger;
import io.openems.edge.logger.huawei.common.HuaweiSmartLoggerProtocol;
import io.openems.edge.logger.huawei.common.InverterRegister;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
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
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Smart.Logger.Huawei", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class HuaweiLoggerImpl extends AbstractOpenemsModbusComponent //
		implements HuaweiLogger, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent {

	private final Set<String> dynamicChannelIds = new HashSet<>();

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public Config config;

	public HuaweiLoggerImpl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				SmartLogger.ChannelId.values(), //
				HuaweiLogger.ChannelId.values() //
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
		var protocol = new ModbusProtocol(this);
		var huaweiProtocol = new HuaweiSmartLoggerProtocol(new HuaweiSmartLoggerProtocol.Host() {
			@Override
			public void addChannel(io.openems.edge.common.channel.ChannelId channelId) {
				if (HuaweiLoggerImpl.this.dynamicChannelIds.add(channelId.id())) {
					HuaweiLoggerImpl.this.addChannel(channelId);
				}
			}

			@Override
			public ModbusElement createElement(io.openems.edge.common.channel.ChannelId channelId, int address,
					InverterRegister.DataType dataType,
					int scaleExponent) {
				return HuaweiLoggerImpl.this.createElement(channelId, address, dataType, scaleExponent);
			}
		}, this.parseInverterAddresses(this.config.inverterAddresses()));
		huaweiProtocol.createReadTasks(new HuaweiSmartLoggerProtocol.AggregateChannels(//
				ElectricityMeter.ChannelId.ACTIVE_POWER, //
				SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL, //
				SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY)) //
				.forEach(protocol::addTask);
		return protocol;
	}

	private List<Integer> parseInverterAddresses(String inverterAddresses) {
		return Arrays.stream(inverterAddresses.split(",")) //
				.map(String::trim) //
				.filter(address -> !address.isEmpty()) //
				.map(this::parseInverterAddress) //
				.filter(address -> address != null) //
				.toList();
	}

	private Integer parseInverterAddress(String address) {
		try {
			var value = Integer.parseInt(address);
			return value > 0 ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private ModbusElement createElement(io.openems.edge.common.channel.ChannelId channelId, int address,
			InverterRegister.DataType dataType,
			int scaleExponent) {
		var converter = channelId == SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY //
				? new ElementToChannelConverter(value -> {
					var raw = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
					return raw == null ? null : raw / 100F;
				})
				: this.getScaleConverter(scaleExponent);
		return switch (dataType) {
		case U16 -> m(channelId, new UnsignedWordElement(address), converter);
		case I16 -> m(channelId, new SignedWordElement(address), converter);
		case U32 -> m(channelId, new UnsignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW), converter);
		case I32 -> m(channelId, new SignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW), converter);
		};
	}

	private ElementToChannelConverter getScaleConverter(int scaleExponent) {
		return switch (scaleExponent) {
		case -3 -> ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
		case -2 -> ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
		case -1 -> ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
		case 0 -> ElementToChannelConverter.DIRECT_1_TO_1;
		case 1 -> ElementToChannelConverter.SCALE_FACTOR_1;
		case 2 -> ElementToChannelConverter.SCALE_FACTOR_2;
		case 3 -> ElementToChannelConverter.SCALE_FACTOR_3;
		default -> throw new IllegalArgumentException("Unsupported scale exponent: " + scaleExponent);
		};
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}
}
