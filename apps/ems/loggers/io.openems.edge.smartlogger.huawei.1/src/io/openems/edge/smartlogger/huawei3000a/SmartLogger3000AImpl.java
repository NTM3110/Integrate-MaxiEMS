package io.openems.edge.smartlogger.huawei3000a;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractModbusBridge;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import com.ghgande.j2mod.modbus.io.ModbusTransaction;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.procimg.Register;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Smart.Logger.Huawei.3000A.1", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class SmartLogger3000AImpl extends AbstractOpenemsModbusComponent implements SmartLogger3000A, ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(SmartLogger3000AImpl.class);

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
	private final List<String> inverterSnList = new ArrayList<>();

	private static final int SN_START_ADDRESS = 30015;
	private static final int SN_REGISTER_LENGTH = 10;
	private static final String PV_INVERTER_FACTORY_PID = "PV-Inverter.Huawei.Hybrid";
	private static final int DISCOVERY_UNIT_ID_START = 1;
	private static final int DISCOVERY_UNIT_ID_END = 255;
	private static final int DEFAULT_COMPONENT_UNIT_ID = 1;
	private static final int INVERTER_BASE_ADDRESS = 51000;
	private static final int INVERTER_REGISTER_GAP = 26;
	private static final int MAX_ACTIVE_POWER_WATTS = 100000;

	private static record DiscoveredInverter(int unitId, String sn) {
	}

	public SmartLogger3000AImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SmartLogger3000A.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		// Discover PV-Inverters first (Unit-ID 1..255), then activate protocol.
		this.inverterAddresses.clear();
		this.inverterSnList.clear();
		List<DiscoveredInverter> discoveredInverters = this.discoverInvertersBySerialNumber();
		if (!discoveredInverters.isEmpty()) {
			this.autoCreateOrUpdatePvInverterComponents(discoveredInverters);
			for (DiscoveredInverter inverter : discoveredInverters) {
				this.inverterAddresses.add(inverter.unitId());
				this.inverterSnList.add(inverter.sn());
				this.addInverterChannels(inverter.unitId());
			}
		}

		/*
		 * Component Unit-ID is derived from discovered devices.
		 * If no inverter is discovered, fallback to 1.
		 */
		int componentUnitId = DEFAULT_COMPONENT_UNIT_ID;
		if (!discoveredInverters.isEmpty()) {
			componentUnitId = discoveredInverters.get(0).unitId();
		}
		if (super.activate(context, config.id(), config.alias(), config.enabled(), componentUnitId, this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}

		this._setMaxActivePower(MAX_ACTIVE_POWER_WATTS);
		this._setMaxApparentPower(MAX_ACTIVE_POWER_WATTS);
	}

	private void addInverterChannels(int addr) {
		for (InverterRegister reg : InverterRegister.values()) {
			String channelName = "pvInverter" + addr + "/" + reg.name;
			this.addChannel(new io.openems.edge.common.channel.ChannelId() {
				@Override
				public String name() {
					return channelName;
				}

				@Override
				public Doc doc() {
					return new IntegerDoc().accessMode(AccessMode.READ_ONLY).unit(reg.unit);
				}
			});
		}
	}

	private List<DiscoveredInverter> discoverInvertersBySerialNumber() {
		List<DiscoveredInverter> discovered = new ArrayList<>();
		Set<String> seenSerialNumbers = new HashSet<>();
		if (!(this.getBridgeModbus() instanceof AbstractModbusBridge bridge)) {
			this.log.warn("Modbus bridge is not ready. Skip inverter auto-discovery.");
			return discovered;
		}

		for (int unitId = DISCOVERY_UNIT_ID_START; unitId <= DISCOVERY_UNIT_ID_END; unitId++) {
			String sn = this.readSerialNumber(bridge, unitId);
			if (sn == null || sn.isBlank()) {
				continue;
			}
			if (seenSerialNumbers.add(sn)) {
				discovered.add(new DiscoveredInverter(unitId, sn));
			}
		}

		if (!discovered.isEmpty()) {
			this.log.info("Auto-discovered {} Huawei inverter(s).", discovered.size());
		}
		return discovered;
	}

	private String readSerialNumber(AbstractModbusBridge bridge, int unitId) {
		ReadMultipleRegistersRequest request = new ReadMultipleRegistersRequest(SN_START_ADDRESS, SN_REGISTER_LENGTH);
		request.setUnitID(unitId);
		try {
			ReadMultipleRegistersResponse response = this.executeReadRegisters(bridge, request);
			if (response == null || response.getRegisters() == null || response.getRegisters().length == 0) {
				return null;
			}
			String sn = this.parseRegistersToAscii(response.getRegisters());
			return this.isValidSerialNumber(sn) ? sn : null;
		} catch (Exception e) {
			return null;
		}
	}

	private ReadMultipleRegistersResponse executeReadRegisters(AbstractModbusBridge bridge, ReadMultipleRegistersRequest request)
			throws Exception {
		try {
			return this.sendReadRegistersRequest(bridge, request);
		} catch (Exception firstError) {
			bridge.closeModbusConnection();
			return this.sendReadRegistersRequest(bridge, request);
		}
	}

	private ReadMultipleRegistersResponse sendReadRegistersRequest(AbstractModbusBridge bridge, ReadMultipleRegistersRequest request)
			throws Exception {
		ModbusTransaction transaction = bridge.getNewModbusTransaction();
		if (transaction == null) {
			return null;
		}
		transaction.setRequest(request);
		transaction.execute();
		if (transaction.getResponse() instanceof ReadMultipleRegistersResponse response) {
			return response;
		}
		throw new OpenemsException("Unexpected Modbus response for inverter SN discovery.");
	}

	private String parseRegistersToAscii(Register[] registers) {
		StringBuilder sb = new StringBuilder(registers.length * 2);
		for (Register reg : registers) {
			int value = reg.getValue();
			char high = (char) ((value >> 8) & 0xFF);
			char low = (char) (value & 0xFF);
			if (high >= 32 && high <= 126) {
				sb.append(high);
			}
			if (low >= 32 && low <= 126) {
				sb.append(low);
			}
		}
		return sb.toString().trim();
	}

	private boolean isValidSerialNumber(String sn) {
		if (sn == null || sn.isBlank()) {
			return false;
		}
		for (int i = 0; i < sn.length(); i++) {
			if (Character.isLetterOrDigit(sn.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	private void autoCreateOrUpdatePvInverterComponents(List<DiscoveredInverter> discoveredInverters) {
		for (int i = 0; i < discoveredInverters.size(); i++) {
			DiscoveredInverter inverter = discoveredInverters.get(i);
			String inverterId = "pvInverter" + (i + 1);
			String inverterAlias = this.config.alias() + " PV Inverter " + (i + 1) + " [SN:" + inverter.sn() + "]";
			try {
				String filter = String.format("(&(%s=%s)(|(id=%s)(&(modbus.id=%s)(modbusUnitId=%d))))", //
						org.osgi.service.cm.ConfigurationAdmin.SERVICE_FACTORYPID, PV_INVERTER_FACTORY_PID, //
						inverterId, this.config.modbus_id(), inverter.unitId());
				org.osgi.service.cm.Configuration[] configs = this.cm.listConfigurations(filter);
				if (configs == null || configs.length == 0) {
					java.util.Dictionary<String, Object> props = new java.util.Hashtable<>();
					props.put("id", inverterId);
					props.put("alias", inverterAlias);
					props.put("enabled", true);
					props.put("modbus.id", this.config.modbus_id());
					props.put("modbusUnitId", inverter.unitId());
					org.osgi.service.cm.Configuration newConfig = this.cm.createFactoryConfiguration(PV_INVERTER_FACTORY_PID, "?");
					newConfig.update(props);
					this.log.info("Auto-created inverter configuration: {} (Unit-ID {}, SN {})", inverterId, inverter.unitId(), inverter.sn());
				} else {
					org.osgi.service.cm.Configuration existingConfig = configs[0];
					java.util.Dictionary<String, Object> props = existingConfig.getProperties();
					if (props == null) {
						props = new java.util.Hashtable<>();
					}
					boolean updated = false;
					if (!inverterId.equals(props.get("id"))) {
						props.put("id", inverterId);
						updated = true;
					}
					if (!inverterAlias.equals(props.get("alias"))) {
						props.put("alias", inverterAlias);
						updated = true;
					}
					if (!this.config.modbus_id().equals(props.get("modbus.id"))) {
						props.put("modbus.id", this.config.modbus_id());
						updated = true;
					}
					if (props.get("modbusUnitId") == null || !Integer.valueOf(inverter.unitId()).equals(props.get("modbusUnitId"))) {
						props.put("modbusUnitId", inverter.unitId());
						updated = true;
					}
					if (updated) {
						existingConfig.update(props);
						this.log.info("Updated inverter configuration: {} (Unit-ID {}, SN {})", inverterId, inverter.unitId(), inverter.sn());
					}
				}
			} catch (Exception e) {
				this.log.error("Failed to auto-create/update inverter config [{}]: {}", inverterId, e.getMessage());
			}
		}
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

		for (int addr : this.inverterAddresses) {
			int baseAddr = INVERTER_BASE_ADDRESS + (addr - 1) * INVERTER_REGISTER_GAP;

			// For simplicity, we create one task per inverter if gap is small.
			List<io.openems.edge.bridge.modbus.api.element.ModbusElement> elements = new ArrayList<>();
			
			// Note: This logic assumes registers are ordered by offset.
			// We iterate through offsets 0-24 (or whatever the gap is)
			for (int offset = 0; offset < INVERTER_REGISTER_GAP; offset++) {
				boolean found = false;
				for (InverterRegister reg : InverterRegister.values()) {
					if (reg.offset == offset) {
						String channelId = "pvInverter" + addr + "/" + reg.name;
						elements.add(this.createElement(reg, baseAddr + offset, channelId));
						if (reg.dataType == InverterRegister.DataType.U32 || reg.dataType == InverterRegister.DataType.I32) {
							offset++; // Skip 	 register for 32-bit values
						}
						found = true;
						break;
					}
				}
				if (!found) {
					elements.add(new io.openems.edge.bridge.modbus.api.element.DummyRegisterElement(baseAddr + offset));
				}
			}

			if (!elements.isEmpty()) {
				protocol.addTask(new FC3ReadRegistersTask(baseAddr, Priority.HIGH, 
						elements.toArray(new io.openems.edge.bridge.modbus.api.element.ModbusElement[0])));
			}
		}

		// Add SmartLogger Aggregate Data (Production) - Table 2-1
		protocol.addTask(new FC3ReadRegistersTask(40525, Priority.HIGH,
				this.createMeterElement(ElectricityMeter.ChannelId.ACTIVE_POWER, 40525, InverterRegister.DataType.I32, 0, Unit.WATT)
		));

		// Add Energy registers (Table 2-5)
		protocol.addTask(new FC3ReadRegistersTask(40560, Priority.HIGH,
				this.createMeterElement(SmartLogger3000A.ChannelId.TOTAL_ENERGY, 40560, InverterRegister.DataType.U32, 2, Unit.WATT_HOURS),
				this.createMeterElement(SmartLogger3000A.ChannelId.DAILY_ENERGY, 40562, InverterRegister.DataType.U32, 2, Unit.WATT_HOURS)
		));


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

	public int getMaxActivePowerWatts() {
		return MAX_ACTIVE_POWER_WATTS;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	private io.openems.edge.bridge.modbus.api.element.ModbusElement createMeterElement(String channelId, int address, InverterRegister.DataType dataType, int scaleExponent, Unit unit) {
		return this.createMeterElement(new io.openems.edge.common.channel.ChannelId() {
			@Override
			public String name() {
				return channelId;
			}

			@Override
			public Doc doc() {
				return new IntegerDoc().unit(unit);
			}
		}, address, dataType, scaleExponent, unit);
	}

	private io.openems.edge.bridge.modbus.api.element.ModbusElement createMeterElement(io.openems.edge.common.channel.ChannelId channelId, int address, InverterRegister.DataType dataType, int scaleExponent, Unit unit) {
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

	private io.openems.edge.bridge.modbus.api.element.ModbusElement createElement(InverterRegister reg, int address, String channelId) {
		switch (reg.dataType) {
		case U16:
			return m(new io.openems.edge.common.channel.ChannelId() {
				@Override
				public String name() {
					return channelId;
				}

				@Override
				public Doc doc() {
					return new IntegerDoc().unit(reg.unit);
				}
			}, new UnsignedWordElement(address), this.getScaleConverter(reg.scaleExponent));
		case I16:
			return m(new io.openems.edge.common.channel.ChannelId() {
				@Override
				public String name() {
					return channelId;
				}

				@Override
				public Doc doc() {
					return new IntegerDoc().unit(reg.unit);
				}
			}, new SignedWordElement(address), this.getScaleConverter(reg.scaleExponent));
		case U32:
			return m(new io.openems.edge.common.channel.ChannelId() {
				@Override
				public String name() {
					return channelId;
				}

				@Override
				public Doc doc() {
					return new IntegerDoc().unit(reg.unit);
				}
			}, new UnsignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW), this.getScaleConverter(reg.scaleExponent));
		case I32:
			return m(new io.openems.edge.common.channel.ChannelId() {
				@Override
				public String name() {
					return channelId;
				}

				@Override
				public Doc doc() {
					return new IntegerDoc().unit(reg.unit);
				}
			}, new SignedDoublewordElement(address).wordOrder(WordOrder.MSWLSW), this.getScaleConverter(reg.scaleExponent));
		default:
			return new io.openems.edge.bridge.modbus.api.element.DummyRegisterElement(address);
		}
	}

	private ElementToChannelConverter getScaleConverter(int scaleExponent) {
		switch (scaleExponent) {
		case 1: return SCALE_FACTOR_1;
		case 2: return SCALE_FACTOR_2;
		case 3: return SCALE_FACTOR_3;
		case -1: return SCALE_FACTOR_MINUS_1;
		case -2: return SCALE_FACTOR_MINUS_2;
		case -3: return SCALE_FACTOR_MINUS_3;
		case 0: return DIRECT_1_TO_1;
		default:
			return new ElementToChannelConverter(value -> {
				if (value == null) {
					return null;
				}
				if (value instanceof Long) {
					return (int) ((Long) value * Math.pow(10, scaleExponent));
				}
				if (value instanceof Integer) {
					return (int) ((Integer) value * Math.pow(10, scaleExponent));
				}
				return value;
			});
		}
	}

	@Override
	public String debugLog() {
		Value<Integer> apLimit = this.getActivePowerLimit();
		return "Limit:" + apLimit.asString();
	}
}
