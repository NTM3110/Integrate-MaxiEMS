package io.openems.edge.inverter.ligoo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.AbstractMultipleWordsElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.pvinverter.api.SystemCommand;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Inverter.Ligoo", service = { LigooInverter.class, RtsPvInverter.class, ElectricityMeter.class,
		HybridInverter.class, HybridInverterControl.class, HybridInverterAlarms.class,
		ManagedSymmetricPvInverter.class, ModbusComponent.class, OpenemsComponent.class,
		EventHandler.class }, immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE })
public class LigooInverterImpl extends AbstractOpenemsModbusComponent implements LigooInverter {

	private static final ElementToChannelConverter TENTH_TO_MILLI = toInteger(100);
	private static final ElementToChannelConverter HUNDREDTH_HZ_TO_MILLIHZ = toInteger(10);
	private static final ElementToChannelConverter TENTH_KW_TO_WATT = toInteger(100);
	private static final ElementToChannelConverter U32_RAW_WATT = toIntegerFromLong(1);
	private static final ElementToChannelConverter U32_TENTH_KWH_TO_WH = toLong(100L);
	private static final ElementToChannelConverter RAW_TENTH_AMPERE_TO_MILLIAMPERE = toInteger(100);
	private static final ElementToChannelConverter RAW_BOOLEAN = new ElementToChannelConverter(value -> {
		if (value == null) {
			return null;
		}
		return ((Number) value).intValue() == 1;
	});
	private static final ElementToChannelConverter RUN_MODE = new ElementToChannelConverter(
			value -> LigooRunMode.toChannelValue((Integer) value));
	private static final ElementToChannelConverter BATTERY_STATE = new ElementToChannelConverter(
			value -> LigooBatteryState.toChannelValue((Integer) value));

	@Reference
	private ConfigurationAdmin cm;
	private LigooInverterProtocol ligooProtocol;
	private Integer ratedPower;
	private Integer runMode;

	public LigooInverterImpl() {
		super(OpenemsComponent.ChannelId.values(), ModbusComponent.ChannelId.values(), ElectricityMeter.ChannelId.values(),
				RtsPvInverter.ChannelId.values(), HybridInverter.ChannelId.values(), HybridInverterControl.ChannelId.values(),
				HybridInverterAlarms.ChannelId.values(), ManagedSymmetricPvInverter.ChannelId.values(),
				LigooInverter.ChannelId.values());
		this._setSupportedAlarmMask(0L);
		this._setAlarmDataValid(false);
	}

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setWriteEnabled(config.writeEnabled());
		this._setSupportedAlarmMask(0L);
		this._setAlarmDataValid(false);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var emsModeRaw = new UnsignedWordElement(13312);
		var chargeCurrentLimitRaw = new UnsignedWordElement(14659);
		var dischargeCurrentLimitRaw = new UnsignedWordElement(14660);
		var systemCommandRaw = new UnsignedWordElement(1025);
		var reserveSocRaw = new UnsignedWordElement(14677);
		var gridChargeEnableRaw = new UnsignedWordElement(14595);
		var activePowerLimitRaw = new UnsignedWordElement(1281);
		var protocol = new LigooInverterProtocol(this, emsModeRaw, chargeCurrentLimitRaw, dischargeCurrentLimitRaw,
				systemCommandRaw, reserveSocRaw, gridChargeEnableRaw, activePowerLimitRaw,
				new FC3ReadRegistersTask(0, Priority.LOW,
						m(LigooInverter.ChannelId.HARDWARE_VERSION, new HardwareVersionElement(0)),
						m(LigooInverter.ChannelId.FIRMWARE_VERSION, new LowWordFirstVersionElement(1))),
				new FC3ReadRegistersTask(22, Priority.HIGH,
						m(HybridInverter.ChannelId.RUN_MODE, new UnsignedWordElement(22), RUN_MODE)),
				new FC3ReadRegistersTask(23, Priority.LOW,
						m(LigooInverter.ChannelId.RATED_POWER, new UnsignedWordElement(23), TENTH_KW_TO_WATT)),
				new FC3ReadRegistersTask(35, Priority.LOW,
						m(LigooInverter.ChannelId.DEVICE_SYSTEM_TYPE, new AsciiWordsElement(35, 10)),
						m(LigooInverter.ChannelId.SERIAL_NUMBER, new AsciiWordsElement(45, 8))),
				new FC3ReadRegistersTask(89, Priority.LOW,
						m(LigooInverter.ChannelId.PROTOCOL_VERSION, new ProtocolVersionElement(89))),
				new FC3ReadRegistersTask(1281, Priority.HIGH,
						m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT, new UnsignedWordElement(1281),
								this.activePowerLimitPercentToWatt())),
				new FC3ReadRegistersTask(1025, Priority.HIGH,
						m(HybridInverterControl.ChannelId.SYSTEM_COMMAND_READBACK, new UnsignedWordElement(1025))),
				new FC3ReadRegistersTask(2062, Priority.HIGH,
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER_L1, new SignedDoublewordElement(2062)),
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER_L2, new SignedDoublewordElement(2064)),
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER_L3, new SignedDoublewordElement(2066))),
				new FC3ReadRegistersTask(12608, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(12608), TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(12613, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_SOC, new UnsignedWordElement(12613))),
				new FC3ReadRegistersTask(12615, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_CHARGE_VOLTAGE_LIMIT, new UnsignedWordElement(12615),
								TENTH_TO_MILLI),
						m(HybridInverter.ChannelId.BATTERY_DISCHARGE_VOLTAGE_LIMIT, new UnsignedWordElement(12616),
								TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(12622, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_STATE, new UnsignedWordElement(12622), BATTERY_STATE)),
				new FC3ReadRegistersTask(12672, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY,
								new LowWordFirstUnsignedDoublewordElement(12672), U32_TENTH_KWH_TO_WH)),
				new FC3ReadRegistersTask(12686, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new LowWordFirstUnsignedDoublewordElement(12686), U32_TENTH_KWH_TO_WH),
						m(HybridInverter.ChannelId.GRID_EXPORT_ENERGY, new LowWordFirstUnsignedDoublewordElement(12688),
								U32_TENTH_KWH_TO_WH),
						m(HybridInverter.ChannelId.GRID_IMPORT_ENERGY, new LowWordFirstUnsignedDoublewordElement(12690),
								U32_TENTH_KWH_TO_WH)),
				new FC3ReadRegistersTask(17152, Priority.HIGH,
						m(LigooInverter.ChannelId.ALARM_ACTIVE_COUNT, new UnsignedWordElement(17152))),
				new FC3ReadRegistersTask(13312, Priority.HIGH,
						m(HybridInverterControl.ChannelId.EMS_MODE_READBACK, new UnsignedWordElement(13312))),
				new FC3ReadRegistersTask(14595, Priority.HIGH,
						m(HybridInverterControl.ChannelId.GRID_CHARGE_ENABLE_READBACK, new UnsignedWordElement(14595),
								RAW_BOOLEAN)),
				new FC3ReadRegistersTask(14659, Priority.HIGH,
						m(HybridInverterControl.ChannelId.CHARGE_CURRENT_LIMIT_READBACK, new UnsignedWordElement(14659),
								RAW_TENTH_AMPERE_TO_MILLIAMPERE),
						m(HybridInverterControl.ChannelId.DISCHARGE_CURRENT_LIMIT_READBACK, new UnsignedWordElement(14660),
								RAW_TENTH_AMPERE_TO_MILLIAMPERE)),
				new FC3ReadRegistersTask(14677, Priority.HIGH,
						m(HybridInverterControl.ChannelId.RESERVE_SOC_READBACK, new UnsignedWordElement(14677))),
				new FC3ReadRegistersTask(20480, Priority.HIGH,
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(20480),
								HUNDREDTH_HZ_TO_MILLIHZ)),
				new FC3ReadRegistersTask(20483, Priority.HIGH,
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(20483), TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(20486, Priority.HIGH,
						m(ElectricityMeter.ChannelId.CURRENT_L1, new SignedWordElement(20486), TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(20526, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(20526))),
				new FC3ReadRegistersTask(20596, Priority.HIGH,
						m(HybridInverter.ChannelId.GENERATOR_FREQUENCY, new UnsignedWordElement(20596),
								HUNDREDTH_HZ_TO_MILLIHZ)),
				new FC3ReadRegistersTask(21844, Priority.HIGH,
						m(RtsPvInverter.ChannelId.DC_POWER, new LowWordFirstUnsignedDoublewordElement(21844),
								U32_RAW_WATT)),
				new FC3ReadRegistersTask(21859, Priority.HIGH,
						m(HybridInverter.ChannelId.GENERATOR_ACTIVE_POWER, new LowWordFirstUnsignedDoublewordElement(21859),
								U32_RAW_WATT)),
				new FC6WriteRegisterTask(13312, emsModeRaw),
				new FC6WriteRegisterTask(14659, chargeCurrentLimitRaw),
				new FC6WriteRegisterTask(14660, dischargeCurrentLimitRaw),
				new FC6WriteRegisterTask(1025, systemCommandRaw),
				new FC6WriteRegisterTask(14677, reserveSocRaw),
				new FC6WriteRegisterTask(14595, gridChargeEnableRaw),
				new FC6WriteRegisterTask(1281, activePowerLimitRaw));
		this.ligooProtocol = protocol;
		this.installWriteListeners(protocol);
		return protocol;
	}

	private void installWriteListeners(LigooInverterProtocol protocol) {
		this.channel(LigooInverter.ChannelId.RATED_POWER).onSetNextValue(value -> {
			this.ratedPower = value.get() instanceof Integer ratedPower ? ratedPower : null;
			this.updateManagedPowerMetadata();
		});
		this.getRunModeChannel().onSetNextValue(value -> {
			this.runMode = value.get();
			this.updateManagedPowerMetadata();
		});
		this.getActivePowerLimitChannel().onSetNextWrite(value -> this.setActivePowerLimitRequest(protocol, value));
		this.getEmsModeRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite()) {
				protocol.emsModeRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (value == null || value < 0 || value > 3) {
				protocol.emsModeRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "EMS mode must be in range 0..3");
				return;
			}
			protocol.emsModeRawElement().setNextWriteValue(value);
			this.accept();
		});
		this.getChargeCurrentLimitRequestChannel().onSetNextWrite(value -> {
			var raw = this.toRawTenthAmpere(value);
			if (!this.acceptsWrite()) {
				protocol.chargeCurrentLimitRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (raw == null) {
				protocol.chargeCurrentLimitRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "Charge current limit must be non-negative 0.1 A steps");
				return;
			}
			protocol.chargeCurrentLimitRawElement().setNextWriteValue(raw);
			this.accept();
		});
		this.getDischargeCurrentLimitRequestChannel().onSetNextWrite(value -> {
			var raw = this.toRawTenthAmpere(value);
			if (!this.acceptsWrite()) {
				protocol.dischargeCurrentLimitRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (raw == null) {
				protocol.dischargeCurrentLimitRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "Discharge current limit must be non-negative 0.1 A steps");
				return;
			}
			protocol.dischargeCurrentLimitRawElement().setNextWriteValue(raw);
			this.accept();
		});
		this.getSystemCommandRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite()) {
				protocol.systemCommandRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (value == null || value != SystemCommand.START.getValue() && value != SystemCommand.STOP.getValue()) {
				protocol.systemCommandRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "System command must be START or STOP");
				return;
			}
			protocol.systemCommandRawElement().setNextWriteValue(value);
			this.accept();
		});
		this.getReserveSocRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite()) {
				protocol.reserveSocRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (value == null || value < 0 || value > 100) {
				protocol.reserveSocRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "Reserve SOC must be in range 0..100");
				return;
			}
			protocol.reserveSocRawElement().setNextWriteValue(value);
			this.accept();
		});
		this.getGridChargeEnableRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite()) {
				protocol.gridChargeEnableRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (value == null) {
				protocol.gridChargeEnableRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "Grid-charge enable must be true or false");
				return;
			}
			protocol.gridChargeEnableRawElement().setNextWriteValue(value.booleanValue() ? 1 : 0);
			this.accept();
		});
	}

	private void updateManagedPowerMetadata() {
		var maximumPercent = modelMaximumPercent(this.runMode);
		if (this.ratedPower == null || this.ratedPower <= 0 || maximumPercent == null) {
			this._setMaxActivePower((Integer) null);
			this._setMaxApparentPower((Integer) null);
			return;
		}
		var maximumPower = (long) this.ratedPower.intValue() * maximumPercent.intValue() / 100L;
		if (maximumPower > Integer.MAX_VALUE) {
			this._setMaxActivePower((Integer) null);
			this._setMaxApparentPower((Integer) null);
			return;
		}
		this._setMaxActivePower((int) maximumPower);
		this._setMaxApparentPower((int) maximumPower);
	}

	private ElementToChannelConverter activePowerLimitPercentToWatt() {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return this.toActivePowerLimitWatt(((Number) value).intValue());
		});
	}

	private Integer toActivePowerLimitWatt(int percent) {
		var maximumPercent = modelMaximumPercent(this.runMode);
		if (this.ratedPower == null || this.ratedPower.intValue() <= 0 || maximumPercent == null || percent < 0
				|| percent > maximumPercent.intValue()) {
			return null;
		}
		var result = (long) this.ratedPower.intValue() * percent / 100L;
		if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
			return null;
		}
		return Integer.valueOf((int) result);
	}

	private Integer toActivePowerLimitPercent(Integer value) {
		var maximumPercent = modelMaximumPercent(this.runMode);
		if (maximumPercent == null || this.ratedPower == null || this.ratedPower.intValue() <= 0) {
			return null;
		}
		if (value == null) {
			return maximumPercent;
		}
		if (value.intValue() < 0) {
			return null;
		}
		if (value.intValue() == 0) {
			return Integer.valueOf(0);
		}
		var percent = (long) value.intValue() * 100L / this.ratedPower.intValue();
		if (percent <= 0L) {
			percent = 1L;
		}
		if (percent < 5L) {
			percent = 5L;
		}
		if (percent > maximumPercent.intValue()) {
			return maximumPercent;
		}
		return Integer.valueOf((int) percent);
	}

	private static Integer modelMaximumPercent(Integer runMode) {
		if (runMode == null) {
			return null;
		}
		return switch (runMode.intValue()) {
		case 2, 31 -> Integer.valueOf(110);
		case 1, 11, 21, 32 -> Integer.valueOf(100);
		default -> null;
		};
	}

	private Integer toRawTenthAmpere(Integer value) {
		if (value == null || value < 0 || value % 100 != 0) {
			return null;
		}
		var raw = value / 100;
		if (raw > 0xFFFF) {
			return null;
		}
		return Integer.valueOf(raw);
	}

	private boolean acceptsWrite() {
		return Boolean.TRUE.equals(this.getWriteEnabled().get());
	}

	private void accept() {
		this._setLastCommandResult(CommandResult.ACCEPTED);
		this._setLastCommandRejectionReason("");
	}

	private void reject(CommandResult result, String reason) {
		this._setLastCommandResult(result);
		this._setLastCommandRejectionReason(reason);
	}

	@Override
	public void handleEvent(Event event) {
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}

	private static ElementToChannelConverter toInteger(int factor) {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return Integer.valueOf(((Number) value).intValue() * factor);
		});
	}

	@Override
	public void setActivePowerLimit(Integer value) throws OpenemsNamedException {
		var protocol = this.ligooProtocol;
		if (protocol == null) {
			throw new OpenemsException("LIGOO Modbus protocol is not initialized");
		}
		this.setActivePowerLimitRequest(protocol, value);
	}

	@Override
	public void setActivePowerLimit(int value) throws OpenemsNamedException {
		this.setActivePowerLimit(Integer.valueOf(value));
	}

	private void setActivePowerLimitRequest(LigooInverterProtocol protocol, Integer value) {
		if (!this.acceptsWrite()) {
			protocol.activePowerLimitRawElement().setNextWriteValue(null);
			this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
			return;
		}
		var raw = this.toActivePowerLimitPercent(value);
		if (raw == null) {
			protocol.activePowerLimitRawElement().setNextWriteValue(null);
			this.reject(CommandResult.REJECTED_RANGE,
					"Active power limit requires non-negative watts and known LIGOO rated power/run mode");
			return;
		}
		protocol.activePowerLimitRawElement().setNextWriteValue(raw);
		this._setActivePowerLimit(this.toActivePowerLimitWatt(raw.intValue()));
		this.accept();
	}

	private static ElementToChannelConverter toIntegerFromLong(int factor) {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			var result = ((Number) value).longValue() * factor;
			if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
				return null;
			}
			return Integer.valueOf((int) result);
		});
	}

	private static ElementToChannelConverter toLong(long factor) {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return Long.valueOf(((Number) value).longValue() * factor);
		});
	}

	private static final class AsciiWordsElement extends AbstractMultipleWordsElement<AsciiWordsElement, String> {

		AsciiWordsElement(int startAddress, int length) {
			super(OpenemsType.STRING, startAddress, length);
		}

		@Override
		protected AsciiWordsElement self() {
			return this;
		}

		@Override
		protected String byteBufferToValue(ByteBuffer buff) {
			var bytes = new byte[buff.remaining()];
			buff.get(bytes);
			return new String(bytes, StandardCharsets.US_ASCII).trim();
		}

		@Override
		protected void valueToByteBuffer(ByteBuffer buff, String value) {
			buff.put(value.getBytes(StandardCharsets.US_ASCII));
		}
	}

	private static final class HardwareVersionElement extends AbstractMultipleWordsElement<HardwareVersionElement, String> {

		HardwareVersionElement(int startAddress) {
			super(OpenemsType.STRING, startAddress, 1);
		}

		@Override
		protected HardwareVersionElement self() {
			return this;
		}

		@Override
		protected String byteBufferToValue(ByteBuffer buff) {
			return Byte.toUnsignedInt(buff.get()) + "." + Byte.toUnsignedInt(buff.get());
		}

		@Override
		protected void valueToByteBuffer(ByteBuffer buff, String value) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class ProtocolVersionElement extends AbstractMultipleWordsElement<ProtocolVersionElement, String> {

		ProtocolVersionElement(int startAddress) {
			super(OpenemsType.STRING, startAddress, 1);
		}

		@Override
		protected ProtocolVersionElement self() {
			return this;
		}

		@Override
		protected String byteBufferToValue(ByteBuffer buff) {
			return Byte.toUnsignedInt(buff.get()) + "." + Byte.toUnsignedInt(buff.get());
		}

		@Override
		protected void valueToByteBuffer(ByteBuffer buff, String value) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class LowWordFirstVersionElement
			extends AbstractMultipleWordsElement<LowWordFirstVersionElement, String> {

		LowWordFirstVersionElement(int startAddress) {
			super(OpenemsType.STRING, startAddress, 2);
		}

		@Override
		protected LowWordFirstVersionElement self() {
			return this;
		}

		@Override
		protected String byteBufferToValue(ByteBuffer buff) {
			var low = Short.toUnsignedInt(buff.getShort());
			var high = Short.toUnsignedInt(buff.getShort());
			return String.join(".", Integer.toString((high >>> 8) & 0xFF), Integer.toString(high & 0xFF),
					Integer.toString((low >>> 8) & 0xFF), Integer.toString(low & 0xFF));
		}

		@Override
		protected void valueToByteBuffer(ByteBuffer buff, String value) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class LowWordFirstUnsignedDoublewordElement
			extends AbstractMultipleWordsElement<LowWordFirstUnsignedDoublewordElement, Long> {

		LowWordFirstUnsignedDoublewordElement(int startAddress) {
			super(OpenemsType.LONG, startAddress, 2);
		}

		@Override
		protected LowWordFirstUnsignedDoublewordElement self() {
			return this;
		}

		@Override
		protected Long byteBufferToValue(ByteBuffer buff) {
			var low = Short.toUnsignedLong(buff.getShort());
			var high = Short.toUnsignedLong(buff.getShort());
			return Long.valueOf(high << 16 | low);
		}

		@Override
		protected void valueToByteBuffer(ByteBuffer buff, Long value) {
			buff.putShort((short) (value.longValue() & 0xFFFF));
			buff.putShort((short) ((value.longValue() >>> 16) & 0xFFFF));
		}
	}
}
