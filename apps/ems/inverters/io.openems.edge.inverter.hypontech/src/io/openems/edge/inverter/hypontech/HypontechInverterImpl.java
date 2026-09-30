package io.openems.edge.inverter.hypontech;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

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
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy.Snapshot;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy.Source;
import io.openems.edge.pvinverter.api.CommandResult;
import io.openems.edge.pvinverter.api.HybridInverter;
import io.openems.edge.pvinverter.api.HybridInverterAlarms;
import io.openems.edge.pvinverter.api.HybridInverterControl;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Inverter.Hypontech", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE,
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class HypontechInverterImpl extends AbstractOpenemsModbusComponent implements HypontechInverter,
		RtsPvInverter, ElectricityMeter, HybridInverter, HybridInverterControl, HybridInverterAlarms, ModbusComponent,
		OpenemsComponent, TimedataProvider {

	private static final ElementToChannelConverter KWH_FLOAT_TO_WH = new ElementToChannelConverter(value -> {
		if (value == null) {
			return null;
		}
		return Math.round(((Number) value).doubleValue() * 1000D);
	});
	private static final ElementToChannelConverter S32_TO_WATT = toInteger(1);
	private static final ElementToChannelConverter TENTH_TO_MILLI = toInteger(100);
	private static final ElementToChannelConverter HUNDREDTH_HZ_TO_MILLIHZ = toInteger(10);
	private static final ElementToChannelConverter PF_TO_THOUSANDTH = toInteger(10);
	private static final ElementToChannelConverter KW_TO_WATT = toInteger(1000);
	private static final ElementToChannelConverter OPERATING_STATE = new ElementToChannelConverter(
			value -> HypontechOperatingState.toChannelValue((Integer) value));
	private static final ElementToChannelConverter BATTERY_STATE = new ElementToChannelConverter(
			value -> HypontechBatteryState.toChannelValue((Integer) value));
	private static final Duration MAXIMUM_DAILY_ENERGY_INTEGRATION_GAP = Duration.ofMinutes(5);
	private static final int WATT_PER_KILOWATT = 1000;
	private final Clock clock;
	private CalculateDailyProductionEnergy dailyProductionEnergy;
	private final HypontechDailyProductionEnergyRestorer dailyProductionEnergyRestorer;
	private final ExecutorService dailyProductionEnergyExecutor;
	private final Object timedataAccessLock = new Object();
	private volatile Timedata timedata;

	private static ElementToChannelConverter toInteger(int factor) {
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			return Integer.valueOf(((Number) value).intValue() * factor);
		});
	}
	@Reference
	private ConfigurationAdmin cm;

	public HypontechInverterImpl() {
		this(Clock.systemDefaultZone(), null, createDailyProductionEnergyExecutor(), true);
	}

	HypontechInverterImpl(Clock clock, Snapshot snapshot) {
		this(clock, snapshot, ForkJoinPool.commonPool(), false);
	}

	HypontechInverterImpl(Clock clock, Snapshot snapshot, Executor executor) {
		this(clock, snapshot, executor, false);
	}

	private HypontechInverterImpl(Clock clock, Snapshot snapshot, Executor executor, boolean ownsExecutor) {
		super(OpenemsComponent.ChannelId.values(), ModbusComponent.ChannelId.values(), ElectricityMeter.ChannelId.values(),
				RtsPvInverter.ChannelId.values(), HybridInverter.ChannelId.values(), HybridInverterControl.ChannelId.values(),
				HybridInverterAlarms.ChannelId.values(), ManagedSymmetricPvInverter.ChannelId.values(),
				HypontechInverter.ChannelId.values());
		this.clock = Objects.requireNonNull(clock);
		this.dailyProductionEnergy = new CalculateDailyProductionEnergy(clock.getZone(),
				MAXIMUM_DAILY_ENERGY_INTEGRATION_GAP, snapshot);
		this.dailyProductionEnergyRestorer = new HypontechDailyProductionEnergyRestorer(clock, executor,
				this::getTimedata, this.timedataAccessLock);
		this.dailyProductionEnergyExecutor = ownsExecutor ? (ExecutorService) executor : null;
		this._setSupportedAlarmMask(0L);
		this._setAlarmDataValid(false);
	}

	private static ExecutorService createDailyProductionEnergyExecutor() {
		return Executors.newSingleThreadExecutor(
				Thread.ofVirtual().name("hypontech-daily-production-energy-restorer-", 0).factory());
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.OPTIONAL, unbind = "unsetTimedata")
	private void setTimedata(Timedata timedata) {
		synchronized (this.timedataAccessLock) {
			this.timedata = timedata;
		}
		this.dailyProductionEnergyRestorer.onTimedataChanged();
	}

	private void unsetTimedata(Timedata timedata) {
		var changed = false;
		synchronized (this.timedataAccessLock) {
			if (this.timedata == timedata) {
				this.timedata = null;
				changed = true;
			}
		}
		if (changed) {
			this.dailyProductionEnergyRestorer.onTimedataChanged();
		}
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
		this.applyMaxActivePower(config.maxActivePower());
		this._setWriteEnabled(config.writeEnabled());
		this._setSupportedAlarmMask(0L);
		this._setAlarmDataValid(false);
		this.dailyProductionEnergyRestorer.activate();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.dailyProductionEnergyRestorer.deactivate();
		if (this.dailyProductionEnergyExecutor != null) {
			this.dailyProductionEnergyExecutor.shutdownNow();
		}
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var activePowerLimitReadback = new ElementToChannelConverter(value -> {
			var watt = KW_TO_WATT.elementToChannel(value);
			this._setActivePowerLimit((Integer) watt);
			return watt;
		});
		var emsModeRaw = new UnsignedWordElement(40907);
		var batteryPowerReferenceRaw = new SignedWordElement(40901);
		var activePowerLimitRaw = new SignedDoublewordElement(40441);
		var reserveSocRaw = new UnsignedWordElement(40903);
		var protocol = new HypontechInverterProtocol(this, emsModeRaw, batteryPowerReferenceRaw, activePowerLimitRaw,
				reserveSocRaw,
					new FC3ReadRegistersTask(30010, Priority.LOW,
							m(HypontechInverter.ChannelId.MANUFACTURER, new AsciiWordsElement(30010, 8)),
							m(HypontechInverter.ChannelId.MODEL, new AsciiWordsElement(30018, 8)),
							m(HypontechInverter.ChannelId.SERIAL_NUMBER, new AsciiWordsElement(30026, 8))),
					new FC3ReadRegistersTask(30039, Priority.LOW,
							m(HypontechInverter.ChannelId.SOFTWARE_VERSION, new VersionElement(30039))),
				new FC3ReadRegistersTask(30100, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(30100), S32_TO_WATT)),
				new FC3ReadRegistersTask(30104, Priority.HIGH,
						m(ElectricityMeter.ChannelId.REACTIVE_POWER, new SignedDoublewordElement(30104), S32_TO_WATT),
						m(RtsPvInverter.ChannelId.POWER_FACTOR, new UnsignedWordElement(30106), PF_TO_THOUSANDTH)),
				new FC3ReadRegistersTask(30108, Priority.HIGH,
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER, new SignedDoublewordElement(30108), S32_TO_WATT)),
				new FC3ReadRegistersTask(30115, Priority.HIGH,
						m(HybridInverter.ChannelId.OPERATING_STATE, new UnsignedWordElement(30115), OPERATING_STATE)),
					new FC3ReadRegistersTask(30127, Priority.HIGH,
							m(RtsPvInverter.ChannelId.DC_POWER, new SignedDoublewordElement(30127), S32_TO_WATT),
							m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, new FloatDoublewordElement(30129),
									KWH_FLOAT_TO_WH),
							m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(30131), TENTH_TO_MILLI),
							m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(30132), TENTH_TO_MILLI),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(30133), TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(30135, Priority.HIGH,
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(30135), TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(30140, Priority.HIGH,
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(30140), HUNDREDTH_HZ_TO_MILLIHZ)),
				new FC3ReadRegistersTask(30156, Priority.HIGH,
						m(HybridInverter.ChannelId.GRID_IMPORT_ENERGY, new FloatDoublewordElement(30156), KWH_FLOAT_TO_WH),
						m(HybridInverter.ChannelId.GRID_EXPORT_ENERGY, new FloatDoublewordElement(30158), KWH_FLOAT_TO_WH)),
				new FC3ReadRegistersTask(30161, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_STATE, new UnsignedWordElement(30161), BATTERY_STATE),
						m(HybridInverter.ChannelId.BATTERY_POWER, new SignedDoublewordElement(30162), S32_TO_WATT),
						m(HybridInverter.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(30164), TENTH_TO_MILLI),
						m(HybridInverter.ChannelId.BATTERY_CURRENT, new SignedWordElement(30165), TENTH_TO_MILLI)),
				new FC3ReadRegistersTask(30174, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_CHARGE_ENERGY, new FloatDoublewordElement(30174),
								KWH_FLOAT_TO_WH),
						m(HybridInverter.ChannelId.BATTERY_DISCHARGE_ENERGY, new FloatDoublewordElement(30176),
								KWH_FLOAT_TO_WH)),
				new FC3ReadRegistersTask(30182, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_SOC, new UnsignedWordElement(30182))),
				new FC3ReadRegistersTask(30186, Priority.HIGH,
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER_L1, new SignedDoublewordElement(30186), S32_TO_WATT),
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER_L2, new SignedDoublewordElement(30188), S32_TO_WATT),
						m(HybridInverter.ChannelId.GRID_ACTIVE_POWER_L3, new SignedDoublewordElement(30190), S32_TO_WATT)),
					new FC3ReadRegistersTask(40441, Priority.HIGH,
							m(HybridInverterControl.ChannelId.ACTIVE_POWER_LIMIT_READBACK,
									new SignedDoublewordElement(40441), activePowerLimitReadback)),
				new FC3ReadRegistersTask(40901, Priority.HIGH,
						m(HybridInverterControl.ChannelId.BATTERY_POWER_REFERENCE_READBACK, new SignedWordElement(40901),
								toInteger(1))),
				new FC3ReadRegistersTask(40903, Priority.HIGH,
						m(HybridInverterControl.ChannelId.RESERVE_SOC_READBACK, new UnsignedWordElement(40903))),
				new FC3ReadRegistersTask(40907, Priority.HIGH,
						m(HybridInverterControl.ChannelId.EMS_MODE_READBACK, new UnsignedWordElement(40907))),
				new FC6WriteRegisterTask(40907, emsModeRaw),
				new FC6WriteRegisterTask(40901, batteryPowerReferenceRaw),
				new FC16WriteRegistersTask(40441, activePowerLimitRaw),
				new FC6WriteRegisterTask(40903, reserveSocRaw));
		this.installWriteListeners(protocol);
		return protocol;
	}

	private void installWriteListeners(HypontechInverterProtocol protocol) {
		this.getEmsModeRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite()) {
				protocol.emsModeRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (value == null || value < 0 || value > 4) {
				protocol.emsModeRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "EMS mode must be in range 0..4");
				return;
			}
			protocol.emsModeRawElement().setNextWriteValue(value);
			this.accept();
		});
		this.getBatteryPowerReferenceRequestChannel().onSetNextWrite(value -> {
			if (!this.acceptsWrite()) {
				protocol.batteryPowerReferenceRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
				return;
			}
			if (value == null || value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
				protocol.batteryPowerReferenceRawElement().setNextWriteValue(null);
				this.reject(CommandResult.REJECTED_RANGE, "Battery power reference must fit signed 0.1 %Pn register");
				return;
			}
			protocol.batteryPowerReferenceRawElement().setNextWriteValue(value.shortValue());
			this.accept();
		});
		this.getActivePowerLimitRequestChannel().onSetNextWrite(value -> this.queueActivePowerLimitRequest(protocol, value));
		this.getActivePowerLimitChannel().onSetNextWrite(value -> {
			var request = value;
			if (request == null) {
				request = this.getMaxActivePower().orElse(null);
				if (request == null) {
					protocol.activePowerLimitRawElement().setNextWriteValue(null);
					this.reject(CommandResult.REJECTED_RANGE, "Active power release requires maximum active power");
					return;
				}
			}
			this.queueActivePowerLimitRequest(protocol, request);
			if (CommandResult.ACCEPTED.getValue() == this.getLastCommandResult().orElse(null)) {
				this._setActivePowerLimit(request);
			}
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
	}

	private void queueActivePowerLimitRequest(HypontechInverterProtocol protocol, Integer value) {
		if (!this.acceptsWrite()) {
			protocol.activePowerLimitRawElement().setNextWriteValue(null);
			this.reject(CommandResult.REJECTED_DISABLED, "Writes are disabled");
			return;
		}
		if (value == null || value < 0) {
			protocol.activePowerLimitRawElement().setNextWriteValue(null);
			this.reject(CommandResult.REJECTED_RANGE, "Active power limit must be non-negative");
			return;
		}
		protocol.activePowerLimitRawElement().setNextWriteValue(Long.valueOf(value / WATT_PER_KILOWATT));
		this.accept();
	}

	private void applyMaxActivePower(int maxActivePower) throws OpenemsException {
		if (maxActivePower <= 0 || maxActivePower % WATT_PER_KILOWATT != 0) {
			throw new OpenemsException("Maximum active power must be a positive whole-kW value");
		}
		this._setMaxActivePower(maxActivePower);
		this._setMaxApparentPower(maxActivePower);
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
		if (!this.isEnabled() || !EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE.equals(event.getTopic())) {
			return;
		}
		var timestamp = this.clock.instant();
		var currentDate = timestamp.atZone(this.clock.getZone()).toLocalDate();
		this.dailyProductionEnergyRestorer.pollSnapshot(currentDate)
				.ifPresent(snapshot -> this.dailyProductionEnergy = new CalculateDailyProductionEnergy(this.clock.getZone(),
						MAXIMUM_DAILY_ENERGY_INTEGRATION_GAP, snapshot));
		var totalEnergy = this.getActiveProductionEnergy().get();
		var result = this.dailyProductionEnergy.update(timestamp, null, totalEnergy, this.getActivePower().get());
		this._setActiveProductionEnergyToday(result.energyWh());
		if (result.source() == Source.UNAVAILABLE && totalEnergy != null && totalEnergy >= 0) {
			this.dailyProductionEnergyRestorer.request(this.id(), currentDate);
		}
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.GRID;
	}

	private static final class VersionElement extends AsciiWordsElement {
		VersionElement(int startAddress) {
			super(startAddress, 2);
		}

		@Override
		protected String byteBufferToValue(java.nio.ByteBuffer buff) {
			return String.join(".", Integer.toString(Byte.toUnsignedInt(buff.get())),
					Integer.toString(Byte.toUnsignedInt(buff.get())), Integer.toString(Byte.toUnsignedInt(buff.get())),
					Integer.toString(Byte.toUnsignedInt(buff.get())));
		}
	}
}
