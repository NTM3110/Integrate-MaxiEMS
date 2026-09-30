package io.openems.edge.smartlogger.huawei3000a;

import org.osgi.service.cm.Configuration;
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

import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.LongDoc;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Smart.Logger.Huawei.3000A", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class SmartLogger3000AImpl extends AbstractOpenemsModbusComponent implements SmartLogger3000A, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(SmartLogger3000AImpl.class);
	private static final int INVERTER_REGISTER_GAP = 25;
	private static final int INVERTER_BASE_ADDRESS = 51000;
	private static final int MAX_REGISTERS_PER_READ = 120;
	private static final String METER_FACTORY_PID = "smartlogger.meter.huawei";

	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;
	private final List<Integer> inverterAddresses = new ArrayList<>();
	private final Map<InverterChannelKey, io.openems.edge.common.channel.ChannelId> inverterChannelIds = new HashMap<>();
	private final Map<Integer, ElementToChannelConverter> scaleConverters = new HashMap<>();

	public SmartLogger3000AImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SmartLogger3000A.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values() //
		);
	}

	@SuppressWarnings("checkstyle:VariableDeclarationUsageDistance")
	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		this.initializeInverters(config.inverterAddresses());

		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.handleMeterConfiguration(config);

		this._setMaxActivePower(config.maxActivePowerWatts());
		this._setMaxApparentPower(config.maxActivePowerWatts());
	}

	private void initializeInverters(String inverterAddressesConfig) {
		this.inverterAddresses.clear();
		Arrays.stream(inverterAddressesConfig.split(",")) //
				.map(String::trim) //
				.filter(s -> !s.isBlank()) //
				.map(this::parseInverterAddress) //
				.flatMap(Optional::stream) //
				.distinct() //
				.sorted() //
				.forEach(address -> {
					this.inverterAddresses.add(address);
					this.addInverterChannels(address);
				});
	}

	private Optional<Integer> parseInverterAddress(String value) {
		try {
			var parsed = Integer.parseInt(value);
			if (parsed <= 0) {
				this.log.warn("Ignoring invalid inverter address [{}]. Address must be > 0.", value);
				return Optional.empty();
			}
			return Optional.of(parsed);
		} catch (NumberFormatException e) {
			this.log.warn("Ignoring invalid inverter address token [{}].", value);
			return Optional.empty();
		}
	}

	private void addInverterChannels(int address) {
		for (var register : InverterRegister.values()) {
			this.getInverterChannelId(address, register);
		}
	}

	private void handleMeterConfiguration(Config config) {
		if (!config.meterEnabled()) {
			return;
		}
		try {
			var number = config.id().replaceAll("[^0-9]", "");
			var meterId = "meter" + number + "_SmartLoggerHuawei";
			var filter = String.format("(&(%s=%s)(|(id=%s)(&(modbus.id=%s)(modbusUnitId=%d))))",
					ConfigurationAdmin.SERVICE_FACTORYPID, METER_FACTORY_PID, meterId, config.modbus_id(),
					config.meterUnitId());

			var configurations = this.cm.listConfigurations(filter);
			if (configurations == null || configurations.length == 0) {
				this.createMeterConfiguration(config, meterId);
				this.log.info("Auto-created Meter configuration [{}].", meterId);
				return;
			}

			var updated = this.updateMeterConfiguration(configurations[0], config, meterId);
			if (updated) {
				this.log.info("Updated Meter configuration [{}].", meterId);
			}
		} catch (Exception e) {
			this.log.error("Failed to auto-create/update Meter configuration.", e);
		}
	}

	/**
	 * a.
	 * @param config a.
	 * @param meterId a.
	 * @throws Exception a .
	 */
	private void createMeterConfiguration(Config config, String meterId) throws Exception {
		Configuration meterConfiguration;
		meterConfiguration = this.cm.createFactoryConfiguration(METER_FACTORY_PID, null);
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("id", meterId);
		properties.put("alias", config.alias() + " Meter");
		properties.put("enabled", true);
		properties.put("modbus.id", config.modbus_id());
		properties.put("modbusUnitId", config.meterUnitId());
		properties.put("invert", config.invert_meter());
		meterConfiguration.update(properties);
	}

	private boolean updateMeterConfiguration(org.osgi.service.cm.Configuration meterConfiguration, Config config,
			String meterId) throws Exception {
		Dictionary<String, Object> properties = meterConfiguration.getProperties();
		if (properties == null) {
			properties = new Hashtable<>();
		}

		var updated = false;
		updated |= this.updateIfChanged(properties, "id", meterId);
		updated |= this.updateIfChanged(properties, "modbus.id", config.modbus_id());
		updated |= this.updateIfChanged(properties, "modbusUnitId", config.meterUnitId());
		updated |= this.updateIfChanged(properties, "invert", config.invert_meter());
		if (updated) {
			meterConfiguration.update(properties);
		}
		return updated;
	}

	private boolean updateIfChanged(Dictionary<String, Object> properties, String key, Object expectedValue) {
		var currentValue = properties.get(key);
		if (expectedValue.equals(currentValue)) {
			return false;
		}
		properties.put(key, expectedValue);
		return true;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
			case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
				try {
					this.setPvLimitHandler.run();
					this.setReactivePowerLimitHandler.run();
				} catch (OpenemsNamedException e) {
					this.logError(this.log, "Error in SmartLogger control handler: " + e.getMessage());
				}
				break;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		ModbusProtocol protocol = new ModbusProtocol(this);
		this.addInverterReadTasks(protocol);

		// Add SmartLogger Aggregate Data (Production) - Table 2-1
		protocol.addTask(new FC3ReadRegistersTask(40525, Priority.HIGH,
				this.createMeterElement(ElectricityMeter.ChannelId.ACTIVE_POWER, 40525, InverterRegister.DataType.I32,
						0)));

		// Add Energy registers (Table 2-5)
		protocol.addTask(new FC3ReadRegistersTask(40560, Priority.HIGH,
				this.createMeterElement(SmartLogger3000A.ChannelId.ENERGY_PRODUCTION_TOTAL, 40560,
						InverterRegister.DataType.U32, 2),
				this.createMeterElement(SmartLogger3000A.ChannelId.ENERGY_PRODUCTION_TODAY, 40562,
						InverterRegister.DataType.U32, 2)));

		// Add Control Write tasks
		protocol.addTask(new FC16WriteRegistersTask(40420, //
				m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT,
						new SignedDoublewordElement(40420).wordOrder(WordOrder.MSWLSW),
						SCALE_FACTOR_2), // Watts to 0.1 kW mapping (Divided by 100 during Write)
				m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT,
						new SignedDoublewordElement(40422).wordOrder(WordOrder.MSWLSW),
						SCALE_FACTOR_2) // var to 0.1 kvar mapping (Divided by 100 during Write)
		));

		return protocol;
	}

	private void addInverterReadTasks(ModbusProtocol protocol) {
		var points = new ArrayList<InverterRegisterPoint>();
		for (var address : this.inverterAddresses) {
			var baseAddress = INVERTER_BASE_ADDRESS + (address - 1) * INVERTER_REGISTER_GAP;
			for (var register : InverterRegister.values()) {
				points.add(new InverterRegisterPoint(baseAddress + register.offset, register,
						this.getInverterChannelId(address, register)));
			}
		}
		points.sort(Comparator.comparingInt(InverterRegisterPoint::address));

		var index = 0;
		while (index < points.size()) {
			var blockStart = points.get(index).address();
			var nextAddress = blockStart;
			var elements = new ArrayList<ModbusElement>();

			while (index < points.size()) {
				var point = points.get(index);
				if (point.address() < nextAddress) {
					index++;
					continue;
				}

				var gap = point.address() - nextAddress;
				var registerLength = this.getRegisterLength(point.register().dataType);
				var projectedLength = (nextAddress - blockStart) + gap + registerLength;
				if (!elements.isEmpty() && projectedLength > MAX_REGISTERS_PER_READ) {
					break;
				}

				if (gap > 0) {
					elements.add(new DummyRegisterElement(nextAddress, point.address() - 1));
					nextAddress = point.address();
				}

				elements.add(this.createElement(point.register(), point.address(), point.channelId()));
				nextAddress += registerLength;
				index++;
			}

			if (!elements.isEmpty()) {
				protocol.addTask(new FC3ReadRegistersTask(blockStart, Priority.HIGH, elements.toArray(ModbusElement[]::new)));
			}
		}
	}

	private int getRegisterLength(InverterRegister.DataType dataType) {
		return switch (dataType) {
		case U16, I16 -> 1;
		case U32, I32 -> 2;
		};
	}

	/**
	 * a.
	 * @return a.
	 */
	public int getMax_Active_Power() {
		return this.config.maxActivePowerWatts();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	private io.openems.edge.common.channel.ChannelId getInverterChannelId(int address, InverterRegister register) {
		return this.inverterChannelIds.computeIfAbsent(new InverterChannelKey(address, register),
				key -> this.createInverterChannelId(key.address(), key.register()));
	}

	private io.openems.edge.common.channel.ChannelId createInverterChannelId(int address, InverterRegister register) {
		var channelName = "_PV_INVERTER" + address + "/" + register.name;
		io.openems.edge.common.channel.ChannelId channelId = new io.openems.edge.common.channel.ChannelId() {

			@Override
			public String name() {
				return channelName;
			}

			@Override
			public Doc doc() {
				if (register.dataType == InverterRegister.DataType.U32) {
					return new LongDoc().accessMode(AccessMode.READ_ONLY).unit(register.unit);
				}
				return new IntegerDoc().accessMode(AccessMode.READ_ONLY).unit(register.unit);
			}
		};
		this.addChannel(channelId);
		return channelId;
	}

	private ModbusElement createMeterElement(io.openems.edge.common.channel.ChannelId channelId, int address,
			InverterRegister.DataType dataType, int scaleExponent) {
		switch (dataType) {
		case U16:
			return m(channelId, new UnsignedWordElement(address), this.getScaleConverter(scaleExponent));
		case I16:
			return m(channelId, new SignedWordElement(address), this.getScaleConverter(scaleExponent));
		case U32:
			return m(channelId, new UnsignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW), this.getScaleConverter(scaleExponent));
		case I32:
			return m(channelId, new SignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW), this.getScaleConverter(scaleExponent));
		default:
			return new io.openems.edge.bridge.modbus.api.element.DummyRegisterElement(address);
		}
	}

	private ModbusElement createElement(InverterRegister reg, int address,
			io.openems.edge.common.channel.ChannelId channelId) {
		switch (reg.dataType) {
		case U16:
			return m(channelId, new UnsignedWordElement(address), this.getScaleConverter(reg.scaleExponent));
		case I16:
			return m(channelId, new SignedWordElement(address), this.getScaleConverter(reg.scaleExponent));
		case U32:
			return m(channelId, new UnsignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW),
					this.getScaleConverter(reg.scaleExponent));
		case I32:
			return m(channelId, new SignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW),
					this.getScaleConverter(reg.scaleExponent));
		default:
			return new io.openems.edge.bridge.modbus.api.element.DummyRegisterElement(address);
		}
	}

	private ElementToChannelConverter getScaleConverter(int scaleExponent) {
		var fixedConverter = switch (scaleExponent) {
		case 1 -> SCALE_FACTOR_1;
		case 2 -> SCALE_FACTOR_2;
		case 3 -> SCALE_FACTOR_3;
		case -1 -> SCALE_FACTOR_MINUS_1;
		case -2 -> SCALE_FACTOR_MINUS_2;
		case -3 -> SCALE_FACTOR_MINUS_3;
		case 0 -> DIRECT_1_TO_1;
		default -> null;
		};
		if (fixedConverter != null) {
			return fixedConverter;
		}
		return this.scaleConverters.computeIfAbsent(scaleExponent, this::createScaleConverter);
	}

	private ElementToChannelConverter createScaleConverter(int scaleExponent) {
		final double factor = Math.pow(10.0d, scaleExponent);
		return new ElementToChannelConverter(value -> {
			if (value == null) {
				return null;
			}
			if (value instanceof Long l) {
				return (int) Math.round(l * factor);
			}
			if (value instanceof Integer i) {
				return (int) Math.round(i * factor);
			}
			return value;
		});
	}

	@Override
	public String debugLog() {
		Value<Integer> apLimit = this.getActivePowerLimit();
		return "Limit:" + apLimit.asString();
	}

	private record InverterChannelKey(int address, InverterRegister register) {
	}

	private record InverterRegisterPoint(int address, InverterRegister register,
			io.openems.edge.common.channel.ChannelId channelId) {
	}
}
