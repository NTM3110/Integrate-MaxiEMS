package io.openems.edge.pvinverter.sungrow.b;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_3;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "io.openems.edge.pvinverter.sungrow.b", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterSungrowImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSungrow, ManagedSymmetricPvInverter, ElectricityMeter, ModbusComponent, OpenemsComponent,
		EventHandler, ModbusSlave, TimedataProvider {
	private final Logger log = LoggerFactory.getLogger(PvInverterSungrowImpl.class);
	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public PvInverterSungrowImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterSungrow.ChannelId.values() //
		);
	}

	@SuppressWarnings("checkstyle:VariableDeclarationUsageDistance")
	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		// 2. Auto-activate Meter component if enabled
		if (config.enableMeter()) {
			try {
				// Derive Meter ID: e.g. "SmartLogger0" -> "meter0_SmartLoggerHuawei"
				String number = config.id().replaceAll("[^0-9]", "");
				String meterId = "meter" + number + "_Sungrow";

				String factoryPid = "meter.sungrow";
				
				// Search for existing config by derived ID OR (ModbusID + UnitID)
				// This finds either an already correct meter OR a legacy meter (e.g. SmartLogger0_Meter)
				String filter = String.format("(&(%s=%s)(| (id=%s) (&(modbus.id=%s)(modbusUnitId=%d)) ))", //
						org.osgi.service.cm.ConfigurationAdmin.SERVICE_FACTORYPID, factoryPid, //
						meterId, config.modbus_id(), config.modbusUnitId());

				org.osgi.service.cm.Configuration[] configs = this.cm.listConfigurations(filter);
				
				if (configs == null || configs.length == 0) {
					// Create new configuration
					org.osgi.service.cm.Configuration newConfig = this.cm.createFactoryConfiguration(factoryPid, null);
					java.util.Dictionary<String, Object> props = new java.util.Hashtable<>(); 					
					props.put("id", meterId);
					props.put("alias", config.alias() + " Meter");
					props.put("enabled", true);
					props.put("modbus.id", config.modbus_id());
					props.put("modbusUnitId", config.modbusUnitId());
					props.put("invertMeter", config.invertMeter());
					newConfig.update(props);
					this.log.info("Auto-created Meter configuration: " + meterId);
					
				} else {
					// Update existing configuration
					// We take the first match. Ideally there is only one.
					org.osgi.service.cm.Configuration existingConfig = configs[0];
					java.util.Dictionary<String, Object> props = existingConfig.getProperties();
					if (props == null) {
						props = new java.util.Hashtable<>();
					}
					
					boolean updated = false;

					// Update ID if needed (Migrates SmartLogger0_Meter -> meter0)
					if (!meterId.equals(props.get("id"))) {
						props.put("id", meterId);
						updated = true;
					}
					
					// Update connection checks
					if (!config.modbus_id().equals(props.get("modbus.id"))) {
						props.put("modbus.id", config.modbus_id());
						updated = true;
					}
					
					Object pUnitId = props.get("modbusUnitId");
					if (pUnitId == null || !pUnitId.equals(config.modbusUnitId())) {
						props.put("modbusUnitId", config.modbusUnitId());
						updated = true;
					}
					
					// Update settings
					Object pInvert = props.get("invert");
					if (pInvert == null || !pInvert.equals(config.invertMeter())) {
						props.put("invert", config.invertMeter());
						updated = true;
					}
					
					if (updated) {
						existingConfig.update(props);
						this.log.info("Updated Meter configuration properties for: " + meterId);
					}
				}
			} catch (Exception e) {
				this.log.error("Failed to auto-create/update Meter configuration: " + e.getMessage());
			}
		}
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		
		this._setMaxApparentPower(config.maxActivePower());

		// Stop if component is disabled
		if (!config.enabled()) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	/**
	 * Gets the Config object.
	 * 
	 * @return the Config
	 */
	public Config getConfig() {
		return this.config;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				new FC4ReadInputRegistersTask(5002, Priority.LOW,
						m(PvInverterSungrow.ChannelId.ENERGY_PRODUCTION_TODAY,
								new UnsignedWordElement(5002), SCALE_FACTOR_2),
						m(PvInverterSungrow.ChannelId.ENERGY_PRODUCTION_TOTAL,
								new UnsignedDoublewordElement(5003).wordOrder(WordOrder.LSWMSW), SCALE_FACTOR_3),
						new DummyRegisterElement(5005, 5006),
						m(PvInverterSungrow.ChannelId.TEMPERATURE,
						new SignedWordElement(5007), SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(5018, Priority.HIGH,						
						m(ElectricityMeter.ChannelId.VOLTAGE_L1,
								new UnsignedWordElement(5018), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2,
								new UnsignedWordElement(5019), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3,
								new UnsignedWordElement(5020), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L1,
								new UnsignedWordElement(5021), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L2,
								new UnsignedWordElement(5022), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L3,
								new UnsignedWordElement(5023), SCALE_FACTOR_2),
						new DummyRegisterElement(5024, 5029),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new UnsignedDoublewordElement(5030).wordOrder(WordOrder.LSWMSW)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(5032).wordOrder(WordOrder.LSWMSW)), // Fixed overlap (starts at 5033)
						m(PvInverterSungrow.ChannelId.POWER_FACTOR,
								new SignedWordElement(5034), SCALE_FACTOR_MINUS_3),
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedWordElement(5035), SCALE_FACTOR_2)),
				new FC6WriteRegisterTask(5006,
						m(PvInverterSungrow.ChannelId.P_LIMITATION_SWITCH, new UnsignedWordElement(5006))),
				new FC6WriteRegisterTask(5007,
						m(PvInverterSungrow.ChannelId.P_LIMITATION_SETTING, new UnsignedWordElement(5007))),
				// Power factor setting at 5019, skip reserved 5009-5018
				new FC6WriteRegisterTask(5035,
						m(PvInverterSungrow.ChannelId.Q_ADJUSTMENT_SWITCH, new UnsignedWordElement(5035))),
				new FC6WriteRegisterTask(5036,
						m(PvInverterSungrow.ChannelId.Q_PERCENTAGE_SETTING, new SignedWordElement(5036))),
				new FC6WriteRegisterTask(5038,
						m(PvInverterSungrow.ChannelId.P_LIMITATION_KW, new UnsignedWordElement(5038))),
				new FC6WriteRegisterTask(5039,
						m(PvInverterSungrow.ChannelId.Q_ADJUSTMENT_KVAR, new SignedWordElement(5039))));
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

					this.channel(PvInverterSungrow.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
				} catch (OpenemsNamedException e) {
					this.channel(PvInverterSungrow.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
				}
				break;
			case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
				Integer activePower = this.getActivePower().get();
				break;
		}
	}

	private void addMeterChannel(String name, Unit unit) {
		this.addChannel(new io.openems.edge.common.channel.ChannelId() {
			@Override public String name() {
				return name; }

			@SuppressWarnings("checkstyle:RightCurly")
			@Override public Doc doc() {
				return new IntegerDoc().unit(unit);
			}
		});
	}


	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	@SuppressWarnings("checkstyle:MethodName")
	private void dctotal() {
		Integer value = (Integer) this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER).value().orElse(null);
		this.channel(PvInverterSungrow.ChannelId.DC_TOTAL).setNextValue(value);
	}

	@SuppressWarnings("checkstyle:EmptyLineSeparator")
	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
