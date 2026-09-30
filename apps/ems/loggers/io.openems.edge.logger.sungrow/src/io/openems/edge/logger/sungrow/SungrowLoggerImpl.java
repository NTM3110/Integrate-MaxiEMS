package io.openems.edge.logger.sungrow;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedQuadruplewordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.logger.api.SmartLogger;
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
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Smart.Logger.Sungrow", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class SungrowLoggerImpl extends AbstractOpenemsModbusComponent //
		implements SungrowLogger, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent {

	// COM100E: low doubleword has swapped word order (word2/word3 exchanged)
	private static final ElementToChannelConverter SUNGROW_U64_LOW_DOUBLEWORD_LSWMSW_TO_INTEGER = //
			new ElementToChannelConverter(value -> {
				var raw = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
				return TypeUtils.getAsType(OpenemsType.INTEGER, fixSungrowU64LowDoublewordWordOrder(raw));
			});
	private static final ElementToChannelConverter SUNGROW_U64_LOW_DOUBLEWORD_LSWMSW_0_1_KWH_TO_WH = //
			new ElementToChannelConverter(value -> {
				var raw = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
				var correctedRaw = fixSungrowU64LowDoublewordWordOrder(raw);
				if (correctedRaw == null) {
					return null;
				}
				return correctedRaw * 100L;
			});

	// Standard: U64 big-endian (no word-swap needed)
	private static final ElementToChannelConverter U64_BIG_ENDIAN_TO_INTEGER = //
			new ElementToChannelConverter(value -> {
				var raw = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
				return TypeUtils.getAsType(OpenemsType.INTEGER, raw);
			});
	private static final ElementToChannelConverter U64_BIG_ENDIAN_0_1_KWH_TO_WH = //
			new ElementToChannelConverter(value -> {
				var raw = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
				if (raw == null) {
					return null;
				}
				return raw * 100L;
			});

	// U32 little-endian (LSWMSW), 0.1 kWh resolution → float kWh
	private static final ElementToChannelConverter U32_0_1_KWH_TO_FLOAT_KWH = new ElementToChannelConverter(
			value -> {
				var raw = TypeUtils.<Long>getAsType(OpenemsType.LONG, value);
				if (raw == null) {
					return null;
				}
				return raw / 10F;
			});

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public Config config;

	public SungrowLoggerImpl() {
		super(OpenemsComponent.ChannelId.values(),//
				ModbusComponent.ChannelId.values(),//
				ElectricityMeter.ChannelId.values(),//
				SmartLogger.ChannelId.values(),//
				SungrowLogger.ChannelId.values(),
				ManagedSymmetricPvInverter.ChannelId.values()//
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
		var isCom100e = this.config.com100e();
		return new ModbusProtocol(this, //
				new FC4ReadInputRegistersTask(8028, Priority.HIGH,
						m(SungrowLogger.ChannelId.DC1_VOLTAGE,
								new SignedWordElement(8028), SCALE_FACTOR_MINUS_2),
						m(SungrowLogger.ChannelId.DC1_CURRENT,
								new SignedWordElement(8029), SCALE_FACTOR_MINUS_2),
						m(SungrowLogger.ChannelId.DC2_VOLTAGE,
								new SignedWordElement(8030)),
						m(SungrowLogger.ChannelId.DC2_CURRENT,
								new SignedWordElement(8031))),
				new FC4ReadInputRegistersTask(8069, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new UnsignedQuadruplewordElement(8069),
								isCom100e ? SUNGROW_U64_LOW_DOUBLEWORD_LSWMSW_TO_INTEGER : U64_BIG_ENDIAN_TO_INTEGER),
						m(SmartLogger.ChannelId.ENERGY_PRODUCTION_TODAY,
								new UnsignedDoublewordElement(8073).wordOrder(WordOrder.LSWMSW),
								U32_0_1_KWH_TO_FLOAT_KWH),
						new DummyRegisterElement(8075, 8078),
						m(SmartLogger.ChannelId.ENERGY_PRODUCTION_TOTAL,
								new UnsignedQuadruplewordElement(8079),
								isCom100e ? SUNGROW_U64_LOW_DOUBLEWORD_LSWMSW_0_1_KWH_TO_WH
										: U64_BIG_ENDIAN_0_1_KWH_TO_WH)));
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	private static Long fixSungrowU64LowDoublewordWordOrder(Long raw) {
		if (raw == null) {
			return null;
		}
		var word0 = (raw >>> 48) & 0xFFFFL;
		var word1 = (raw >>> 32) & 0xFFFFL;
		var word2 = (raw >>> 16) & 0xFFFFL;
		var word3 = raw & 0xFFFFL;
		// COM100E quirk: word2 and word3 of the lower doubleword are swapped
		return (word0 << 48) | (word1 << 32) | (word3 << 16) | word2;
	}
}
