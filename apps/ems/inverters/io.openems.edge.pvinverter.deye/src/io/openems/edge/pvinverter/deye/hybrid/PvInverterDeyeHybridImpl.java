package io.openems.edge.pvinverter.deye.hybrid;

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

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedQuadruplewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PV-Inverter.Deye.Hybrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { "type=PRODUCTION" })
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class PvInverterDeyeHybridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterDeyeHybrid, ManagedSymmetricPvInverter, ElectricityMeter, HybridInverter,
		HybridInverterControl, HybridInverterAlarms, ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave,
		TimedataProvider {

	private static final int MAX_UNSIGNED_WORD = 0xFFFF;
	private static final int MILLIAMPERE_PER_AMPERE = 1000;

	private static final ElementToChannelConverter TENTH_VOLT_TO_MILLIVOLT = toInteger(100);
	private static final ElementToChannelConverter HUNDREDTH_AMPERE_TO_MILLIAMPERE = toInteger(10);
	private static final ElementToChannelConverter AMPERE_TO_MILLIAMPERE = toInteger(MILLIAMPERE_PER_AMPERE);
	private static final ElementToChannelConverter HUNDREDTH_KILOWATT_TO_WATT = toInteger(10);
	private static final ElementToChannelConverter TENTH_HERTZ_TO_MILLIHERTZ = toInteger(100);
	private static final ElementToChannelConverter TENTH_KILOWATT_HOUR_TO_WATT_HOUR = toLong(100);

	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);
	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;

	private Config config;

	public PvInverterDeyeHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				HybridInverter.ChannelId.values(), //
				HybridInverterControl.ChannelId.values(), //
				HybridInverterAlarms.ChannelId.values(), //
				legacyChannelIds());
		this._setSupportedAlarmMask(0L);
		this._setAlarmDataValid(false);
	}

	private static PvInverterDeyeHybrid.ChannelId[] legacyChannelIds() {
		return java.util.Arrays.stream(PvInverterDeyeHybrid.ChannelId.values()) //
				.filter(channelId -> switch (channelId) {
				case BATTERY_SOC, BATTERY_VOLTAGE, BATTERY_CURRENT, BATTERY_POWER, BATTERY_CAPACITY -> false;
				default -> true;
				}) //
				.toArray(PvInverterDeyeHybrid.ChannelId[]::new);
	}

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.validateConfig(config);
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setMaxApparentPower(config.maxActivePower());
		this._setMaxActivePower(config.maxActivePower());
		this._setWriteEnabled(config.writeEnabled());
		this._setSupportedAlarmMask(0L);
		this._setAlarmDataValid(false);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private void validateConfig(Config config) throws OpenemsException {
		if (config.maxActivePower() <= 0) {
			throw new OpenemsException("Maximum active power must be positive");
		}
		if (config.maxBatteryPowerReference() <= 0 || config.maxBatteryPowerReference() > 1000) {
			throw new OpenemsException("Maximum battery reference must be in range 1..1000 (0.1 %Pn)");
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var activePowerLimitRaw = new UnsignedWordElement(77);
		var chargeCurrentLimitRaw = new UnsignedWordElement(108);
		var dischargeCurrentLimitRaw = new UnsignedWordElement(109);
		var reserveSocRaw = new UnsignedWordElement(117);
		var gridChargeEnableRaw = new UnsignedWordElement(130);
		var emsModeRaw = new UnsignedWordElement(141);
		var remoteEnableRaw = new UnsignedWordElement(1100);
		var heartbeatRaw = new UnsignedWordElement(1101);
		var batteryPowerReferenceRaw = new SignedWordElement(1109);

		var activePowerLimitReadback = new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return this.fromPermille(((Number) value).intValue());
		});
		var gridChargeEnableReadback = new ElementToChannelConverter(
				value -> value == null ? null : ((Number) value).intValue() != 0);

		var protocol = new DeyeHybridProtocol(this, activePowerLimitRaw, chargeCurrentLimitRaw,
				dischargeCurrentLimitRaw, reserveSocRaw, gridChargeEnableRaw, emsModeRaw, remoteEnableRaw,
				heartbeatRaw, batteryPowerReferenceRaw,
				new FC3ReadRegistersTask(3, Priority.LOW, //
						m(PvInverterDeyeHybrid.ChannelId.S_N, new StringWordElement(3, 5)), //
						m(PvInverterDeyeHybrid.ChannelId.RATE_POWER, new UnsignedWordElement(8))),
				new FC3ReadRegistersTask(77, Priority.HIGH, //
						m(HybridInverterControl.ChannelId.ACTIVE_POWER_LIMIT_READBACK,
								new UnsignedWordElement(77), activePowerLimitReadback)),
				new FC3ReadRegistersTask(108, Priority.HIGH, //
						m(HybridInverterControl.ChannelId.CHARGE_CURRENT_LIMIT_READBACK,
								new UnsignedWordElement(108), AMPERE_TO_MILLIAMPERE),
						m(HybridInverterControl.ChannelId.DISCHARGE_CURRENT_LIMIT_READBACK,
								new UnsignedWordElement(109), AMPERE_TO_MILLIAMPERE)),
				new FC3ReadRegistersTask(117, Priority.HIGH, //
						m(HybridInverterControl.ChannelId.RESERVE_SOC_READBACK, new UnsignedWordElement(117))),
				new FC3ReadRegistersTask(130, Priority.HIGH, //
						m(HybridInverterControl.ChannelId.GRID_CHARGE_ENABLE_READBACK, new UnsignedWordElement(130),
								gridChargeEnableReadback)),
				new FC3ReadRegistersTask(141, Priority.HIGH, //
						m(HybridInverterControl.ChannelId.EMS_MODE_READBACK, new UnsignedWordElement(141))),
				new FC3ReadRegistersTask(218, Priority.HIGH, //
						m(PvInverterDeyeHybrid.ChannelId.BMS_MAX_CHARGE_CURRENT, new UnsignedWordElement(218),
								AMPERE_TO_MILLIAMPERE),
						m(PvInverterDeyeHybrid.ChannelId.BMS_MAX_DISCHARGE_CURRENT, new UnsignedWordElement(219),
								AMPERE_TO_MILLIAMPERE),
						new DummyRegisterElement(220, 221),
						m(PvInverterDeyeHybrid.ChannelId.BMS_COMMUNICATION_STATUS,
								new UnsignedWordElement(222))),
				new FC3ReadRegistersTask(501, Priority.HIGH, //
						m(PvInverterDeyeHybrid.ChannelId.ENERGY_PRODUCTION_TODAY, new SignedWordElement(501),
								toInteger(100)),
						new DummyRegisterElement(502, 503),
						m(PvInverterDeyeHybrid.ChannelId.ENERGY_PRODUCTION_TOTAL,
								new SignedDoublewordElement(504).wordOrder(WordOrder.LSWMSW), toInteger(100))),
				new FC3ReadRegistersTask(516, Priority.HIGH, //
						m(HybridInverter.ChannelId.BATTERY_CHARGE_ENERGY,
								new UnsignedDoublewordElement(516).wordOrder(WordOrder.LSWMSW),
								TENTH_KILOWATT_HOUR_TO_WATT_HOUR),
						m(HybridInverter.ChannelId.BATTERY_DISCHARGE_ENERGY,
								new UnsignedDoublewordElement(518).wordOrder(WordOrder.LSWMSW),
								TENTH_KILOWATT_HOUR_TO_WATT_HOUR),
						new DummyRegisterElement(520, 521),
						m(HybridInverter.ChannelId.GRID_IMPORT_ENERGY,
								new UnsignedDoublewordElement(522).wordOrder(WordOrder.LSWMSW),
								TENTH_KILOWATT_HOUR_TO_WATT_HOUR),
						m(HybridInverter.ChannelId.GRID_EXPORT_ENERGY,
								new UnsignedDoublewordElement(524).wordOrder(WordOrder.LSWMSW),
								TENTH_KILOWATT_HOUR_TO_WATT_HOUR)),
				new FC3ReadRegistersTask(540, Priority.HIGH, //
						m(PvInverterDeyeHybrid.ChannelId.TEMPERATURE, new SignedWordElement(540),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC3ReadRegistersTask(553, Priority.LOW, //
						m(PvInverterDeyeHybrid.ChannelId.WARNING_BITMAP,
								new UnsignedDoublewordElement(553).wordOrder(WordOrder.LSWMSW)),
						m(PvInverterDeyeHybrid.ChannelId.FAULT_BITMAP, new UnsignedQuadruplewordElement(555))),
				new FC3ReadRegistersTask(587, Priority.HIGH, //
						m(HybridInverter.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(587),
								TENTH_VOLT_TO_MILLIVOLT),
						m(HybridInverter.ChannelId.BATTERY_SOC, new UnsignedWordElement(588)),
						new DummyRegisterElement(589, 589),
						m(HybridInverter.ChannelId.BATTERY_POWER, new SignedWordElement(590),
								HUNDREDTH_KILOWATT_TO_WATT),
						m(HybridInverter.ChannelId.BATTERY_CURRENT, new SignedWordElement(591),
								HUNDREDTH_AMPERE_TO_MILLIAMPERE)),
				new FC3ReadRegistersTask(598, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(598), toInteger(100)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(599), toInteger(100)),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(600), toInteger(100)),
						new DummyRegisterElement(601, 609),
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(610), toInteger(10)),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(611), toInteger(10)),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(612), toInteger(10))),
				new FC3ReadRegistersTask(621, Priority.HIGH, //
						m(PvInverterDeyeHybrid.ChannelId.PF, new UnsignedWordElement(621),
								ElementToChannelConverter.SCALE_FACTOR_3),
						new DummyRegisterElement(622, 635),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedWordElement(636),
								HUNDREDTH_KILOWATT_TO_WATT),
						m(ElectricityMeter.ChannelId.APPARENT_POWER, new SignedWordElement(637),
								HUNDREDTH_KILOWATT_TO_WATT),
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(638),
								TENTH_HERTZ_TO_MILLIHERTZ)),
				new FC3ReadRegistersTask(676, Priority.HIGH, //
						m(PvInverterDeyeHybrid.ChannelId.DC1_VOLTAGE, new UnsignedWordElement(676), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC1_AMPERE, new UnsignedWordElement(677), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC2_VOLTAGE, new UnsignedWordElement(678), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC2_AMPERE, new UnsignedWordElement(679), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC3_VOLTAGE, new UnsignedWordElement(680), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC3_AMPERE, new UnsignedWordElement(681), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC4_VOLTAGE, new UnsignedWordElement(682), toInteger(100)),
						m(PvInverterDeyeHybrid.ChannelId.DC4_AMPERE, new UnsignedWordElement(683), toInteger(100))),
				new FC3ReadRegistersTask(1100, Priority.HIGH, //
						m(PvInverterDeyeHybrid.ChannelId.REMOTE_ENABLE_READBACK, new UnsignedWordElement(1100)),
						m(PvInverterDeyeHybrid.ChannelId.HEARTBEAT_READBACK, new UnsignedWordElement(1101))),
				new FC3ReadRegistersTask(1109, Priority.HIGH, //
						m(HybridInverterControl.ChannelId.BATTERY_POWER_REFERENCE_READBACK,
								new SignedWordElement(1109))),
				new FC6WriteRegisterTask(77, activePowerLimitRaw),
				new FC6WriteRegisterTask(108, chargeCurrentLimitRaw),
				new FC6WriteRegisterTask(109, dischargeCurrentLimitRaw),
				new FC6WriteRegisterTask(117, reserveSocRaw),
				new FC6WriteRegisterTask(130, gridChargeEnableRaw),
				new FC6WriteRegisterTask(141, emsModeRaw),
				new FC6WriteRegisterTask(1100, remoteEnableRaw),
				new FC6WriteRegisterTask(1101, heartbeatRaw),
				new FC6WriteRegisterTask(1109, batteryPowerReferenceRaw));
		this.installWriteListeners(protocol);
		return protocol;
	}

	private void installWriteListeners(DeyeHybridProtocol protocol) {
		this.getActivePowerLimitRequestChannel()
				.onSetNextWrite(value -> this.queueActivePowerLimit(protocol, value));
		this.getActivePowerLimitChannel().onSetNextWrite(value -> {
			var requestedValue = value == null ? this.config.maxActivePower() : value;
			this.queueActivePowerLimit(protocol, requestedValue);
			if (this.isAccepted()) {
				this._setActivePowerLimit(requestedValue);
			}
		});
		this.getChargeCurrentLimitRequestChannel()
				.onSetNextWrite(value -> this.queueCurrentLimit(protocol.chargeCurrentLimitRawElement(), value,
						"Charge current limit"));
		this.getDischargeCurrentLimitRequestChannel()
				.onSetNextWrite(value -> this.queueCurrentLimit(protocol.dischargeCurrentLimitRawElement(), value,
						"Discharge current limit"));
		this.getMaxChargeCurrentLimitChannel().onSetNextWrite(value -> this.queueCurrentLimit(
				protocol.chargeCurrentLimitRawElement(), value == null ? null : value * MILLIAMPERE_PER_AMPERE,
				"Charge current limit"));
		this.getMaxDischargeCurrentLimitChannel().onSetNextWrite(value -> this.queueCurrentLimit(
				protocol.dischargeCurrentLimitRawElement(), value == null ? null : value * MILLIAMPERE_PER_AMPERE,
				"Discharge current limit"));
		this.getReserveSocRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite(protocol.reserveSocRawElement())) {
				return;
			}
			if (value == null || value < 0 || value > 100) {
				this.reject(protocol.reserveSocRawElement(), CommandResult.REJECTED_RANGE,
						"Reserve SOC must be in range 0..100");
				return;
			}
			protocol.reserveSocRawElement().setNextWriteValue(value);
			this.accept();
		});
		this.getGridChargeEnableRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite(protocol.gridChargeEnableRawElement())) {
				return;
			}
			if (value == null) {
				this.reject(protocol.gridChargeEnableRawElement(), CommandResult.REJECTED_RANGE,
						"Grid-charge enable must be true or false");
				return;
			}
			protocol.gridChargeEnableRawElement().setNextWriteValue(value.booleanValue() ? 1 : 0);
			this.accept();
		});
		this.getEmsModeRequestChannel().onSetNextWrite(value -> this.queueUnsignedWord(protocol.emsModeRawElement(),
				value, "EMS mode"));
		this.getRemoteEnableRequestChannel().onSetNextWrite(value -> this.queueUnsignedWord(
				protocol.remoteEnableRawElement(), value, "Remote enable"));
		this.getHeartbeatRequestChannel().onSetNextWrite(value -> this.queueUnsignedWord(protocol.heartbeatRawElement(),
				value, "Heartbeat"));
		this.getBatteryPowerReferenceRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite(protocol.batteryPowerReferenceRawElement())) {
				return;
			}
			if (value == null || Math.abs((long) value) > this.config.maxBatteryPowerReference()) {
				this.reject(protocol.batteryPowerReferenceRawElement(), CommandResult.REJECTED_RANGE,
						"Battery reference exceeds configured absolute 0.1 %Pn limit");
				return;
			}
			protocol.batteryPowerReferenceRawElement().setNextWriteValue(value.shortValue());
			this.accept();
		});
	}

	private void queueActivePowerLimit(DeyeHybridProtocol protocol, Integer value) {
		if (!this.acceptsWrite(protocol.activePowerLimitRawElement())) {
			return;
		}
		if (value == null || value < 0 || value > this.config.maxActivePower()) {
			this.reject(protocol.activePowerLimitRawElement(), CommandResult.REJECTED_RANGE,
					"Active power limit must be in range 0..maximum active power");
			return;
		}
		protocol.activePowerLimitRawElement().setNextWriteValue(this.toPermille(value));
		this.accept();
	}

	private void queueCurrentLimit(UnsignedWordElement element, Integer value, String label) {
		if (!this.acceptsWrite(element)) {
			return;
		}
		if (value == null || value < 0 || value % MILLIAMPERE_PER_AMPERE != 0
				|| value / MILLIAMPERE_PER_AMPERE > MAX_UNSIGNED_WORD) {
			this.reject(element, CommandResult.REJECTED_RANGE, label + " must use non-negative whole-ampere steps");
			return;
		}
		element.setNextWriteValue(value / MILLIAMPERE_PER_AMPERE);
		this.accept();
	}

	private void queueUnsignedWord(UnsignedWordElement element, Integer value, String label) {
		if (!this.acceptsWrite(element)) {
			return;
		}
		if (value == null || value < 0 || value > MAX_UNSIGNED_WORD) {
			this.reject(element, CommandResult.REJECTED_RANGE, label + " must fit an unsigned 16-bit register");
			return;
		}
		element.setNextWriteValue(value);
		this.accept();
	}

	private boolean acceptsWrite(io.openems.edge.bridge.modbus.api.element.ModbusRegisterElement<?, ?> element) {
		if (Boolean.TRUE.equals(this.getWriteEnabled().get())) {
			return true;
		}
		this.reject(element, CommandResult.REJECTED_DISABLED, "Writes are disabled");
		return false;
	}

	private void reject(io.openems.edge.bridge.modbus.api.element.ModbusRegisterElement<?, ?> element,
			CommandResult result, String reason) {
		element.setNextWriteValue(null);
		this._setLastCommandResult(result);
		this._setLastCommandRejectionReason(reason);
	}

	private void accept() {
		this._setLastCommandResult(CommandResult.ACCEPTED);
		this._setLastCommandRejectionReason("");
	}

	private boolean isAccepted() {
		return this.getLastCommandResult().orElse(CommandResult.NONE.getValue()) == CommandResult.ACCEPTED.getValue();
	}

	private int toPermille(int watt) {
		return (int) Math.round((double) watt * 1000D / this.config.maxActivePower());
	}

	private int fromPermille(int permille) {
		return (int) Math.round((double) permille * this.config.maxActivePower() / 1000D);
	}

	private static ElementToChannelConverter toInteger(int factor) {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return Math.toIntExact(((Number) value).longValue() * factor);
		});
	}

	private static ElementToChannelConverter toLong(int factor) {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return Math.multiplyExact(((Number) value).longValue(), factor);
		});
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled() || !EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE.equals(event.getTopic())) {
			return;
		}
		this.calculateProductionEnergy.update(this.getActivePower().get());
		this.mirrorLegacyBatteryChannels();
	}

	private void mirrorLegacyBatteryChannels() {
		this.channel(PvInverterDeyeHybrid.ChannelId.BATTERY_CURRENTLIMIT_CHARGE)
				.setNextValue(toFloat(this.getBmsMaxChargeCurrent().get(), 1000F));
		this.channel(PvInverterDeyeHybrid.ChannelId.BATTERY_CURRENTLIMIT_DISCHARGE)
				.setNextValue(toFloat(this.getBmsMaxDischargeCurrent().get(), 1000F));
		this.channel(PvInverterDeyeHybrid.ChannelId.BATTERY_ENERGYCHARGE_TOTAL)
				.setNextValue(toFloat(this.getBatteryChargeEnergy().get(), 1000F));
		this.channel(PvInverterDeyeHybrid.ChannelId.BATTERY_ENERGYDISCHARGE_TOTAL)
				.setNextValue(toFloat(this.getBatteryDischargeEnergy().get(), 1000F));
	}

	private static Float toFloat(Number value, float divisor) {
		return value == null ? null : value.floatValue() / divisor;
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public String debugLog() {
		return "PV:" + this.getActivePower().asString() //
				+ "|Bat:" + this.getBatteryPower().asString() //
				+ "|SoC:" + this.getBatterySoc().asString();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
