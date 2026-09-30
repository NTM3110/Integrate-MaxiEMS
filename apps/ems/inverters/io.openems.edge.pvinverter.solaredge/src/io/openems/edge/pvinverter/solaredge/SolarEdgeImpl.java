package io.openems.edge.pvinverter.solaredge;

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
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.channel.value.Value;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.function.BiConsumer;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "pvinverter.solaredge.modbus", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class SolarEdgeImpl extends AbstractOpenemsModbusComponent
		implements SolarEdge, ManagedSymmetricPvInverter, ModbusComponent, OpenemsComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(SolarEdgeImpl.class);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	protected volatile Timedata timedata = null;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	protected Config config;
	private SetPvLimitHandler setPvLimitHandler = null;
	private LocalDate lastDay = null;
	private Float startOfDayTotalEnergy = null;

	public SolarEdgeImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				SolarEdge.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
		this.config = config;
		if (!config.enabled()) {
			return;
		}
		this.setPvLimitHandler = new SetPvLimitHandler(this);
		this.activateScaleFactorLogic();
		this.calculateEnergyProductionToday();
		this.updateMeterConfig();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(40071, Priority.HIGH,
						m(SolarEdge.ChannelId.RAW_CURRENT,
								new UnsignedWordElement(40071)),
						m(SolarEdge.ChannelId.RAW_CURRENT_L1,
								new UnsignedWordElement(40072)),
						m(SolarEdge.ChannelId.RAW_CURRENT_L2,
								new UnsignedWordElement(40073)),
						m(SolarEdge.ChannelId.RAW_CURRENT_L3,
								new UnsignedWordElement(40074)),
						m(SolarEdge.ChannelId.CURRENT_SF,
								new SignedWordElement(40075)),
						m(SolarEdge.ChannelId.RAW_VOLTAGE_L1_L2,
								new UnsignedWordElement(40076)),
						m(SolarEdge.ChannelId.RAW_VOLTAGE_L2_L3,
								new UnsignedWordElement(40077)),
						m(SolarEdge.ChannelId.RAW_VOLTAGE_L3_L1,
								new UnsignedWordElement(40078)),
						m(SolarEdge.ChannelId.RAW_VOLTAGE_L1,
								new UnsignedWordElement(40079)),
						m(SolarEdge.ChannelId.RAW_VOLTAGE_L2,
								new UnsignedWordElement(40080)),
						m(SolarEdge.ChannelId.RAW_VOLTAGE_L3,
								new UnsignedWordElement(40081)),
						m(SolarEdge.ChannelId.VOLTAGE_SF,
								new SignedWordElement(40082)),
						m(SolarEdge.ChannelId.RAW_ACTIVE_POWER,
								new SignedWordElement(40083)),
						m(SolarEdge.ChannelId.ACTIVE_POWER_SF,
								new SignedWordElement(40084)),
						m(SolarEdge.ChannelId.RAW_FREQUENCY,
								new UnsignedWordElement(40085)),
						m(SolarEdge.ChannelId.FREQUENCY_SF,
								new SignedWordElement(40086)),
						m(SolarEdge.ChannelId.RAW_APPARENT_POWER,
								new SignedWordElement(40087)),
						m(SolarEdge.ChannelId.APPARENT_POWER_SF,
								new SignedWordElement(40088)),
						m(SolarEdge.ChannelId.RAW_REACTIVE_POWER,
								new SignedWordElement(40089)),
						m(SolarEdge.ChannelId.REACTIVE_POWER_SF,
								new SignedWordElement(40090)),
						m(SolarEdge.ChannelId.RAW_POWER_FACTOR,
								new SignedWordElement(40091)),
						m(SolarEdge.ChannelId.POWER_FACTOR_SF,
								new SignedWordElement(40092))),
				new FC3ReadRegistersTask(40093, Priority.HIGH,
						m(SolarEdge.ChannelId.RAW_ENERGY_PRODUCTION_TOTAL,
								new UnsignedDoublewordElement(40093).wordOrder(WordOrder.MSWLSW))),
				new FC3ReadRegistersTask(40095, Priority.LOW,
						m(SolarEdge.ChannelId.ENERGY_PRODUCTION_TOTAL_SF,
								new UnsignedWordElement(40095)),
						m(SolarEdge.ChannelId.RAW_DC1_CURRENT,
								new UnsignedWordElement(40096)),
						m(SolarEdge.ChannelId.DC1_CURRENT_SF,
								new SignedWordElement(40097)),
						m(SolarEdge.ChannelId.RAW_DC1_VOLTAGE,
								new UnsignedWordElement(40098)),
						m(SolarEdge.ChannelId.DC1_VOLTAGE_SF,
								new SignedWordElement(40099)),
						m(SolarEdge.ChannelId.RAW_DC1_POWER,
								new SignedWordElement(40100)),
						m(SolarEdge.ChannelId.DC1_POWER_SF,
								new SignedWordElement(40101))),
				new FC3ReadRegistersTask(40103, Priority.LOW,
						m(SolarEdge.ChannelId.RAW_TEMPERATURE,
								new SignedWordElement(40103))),
				new FC3ReadRegistersTask(40106, Priority.LOW,
						m(SolarEdge.ChannelId.TEMPERATURE_SF,
								new SignedWordElement(40106))),
					new FC16WriteRegistersTask(0xF30C, //
							m(SolarEdge.ChannelId.LIMIT_ACTIVE_POWER,
									new FloatDoublewordElement(0xF30C).wordOrder(WordOrder.MSWLSW))), //
					new FC16WriteRegistersTask(0xF30E, //
							m(SolarEdge.ChannelId.LIMIT_REACTIVE_POWER,
									new FloatDoublewordElement(0xF30E).wordOrder(WordOrder.MSWLSW))));
	}

	private void calculateEnergyProductionToday() {
		Channel<Float> totalEnergyChannel = this.channel(SolarEdge.ChannelId.ENERGY_PRODUCTION_TOTAL);
		Channel<Float> todayEnergyChannel = this.channel(SolarEdge.ChannelId.ENERGY_PRODUCTION_TODAY);

		totalEnergyChannel.onChange((oldValue, newValue) -> {
			if (!newValue.isDefined()) {
				return;
			}

			Float currentTotalEnergy = newValue.get();
			LocalDate currentDay = LocalDate.now();

			if (this.lastDay == null || !this.lastDay.isEqual(currentDay)) {
				this.lastDay = currentDay;
				this.startOfDayTotalEnergy = this.getStartOfDayTotalEnergy(currentDay, currentTotalEnergy);
			}

			if (this.startOfDayTotalEnergy != null && currentTotalEnergy != null) {
				Float todayEnergy = currentTotalEnergy - this.startOfDayTotalEnergy;
				if (todayEnergy < 0) {
					todayEnergy = 0f;
				}
				todayEnergyChannel.setNextValue(todayEnergy);
			}
		});
	}

	private Float getStartOfDayTotalEnergy(LocalDate currentDay, Float fallbackValue) {
		var timedata = this.timedata;
		if (timedata == null) {
			return fallbackValue;
		}

		var zoneId = ZoneId.systemDefault();
		var fromDate = currentDay.atStartOfDay(zoneId);
		var toDate = ZonedDateTime.now(zoneId);
		var channelAddress = new ChannelAddress(this.id(), SolarEdge.ChannelId.ENERGY_PRODUCTION_TOTAL.id());

		try {
			var historicData = timedata.queryHistoricData(null, fromDate, toDate, Set.of(channelAddress),
					new Resolution(1, ChronoUnit.MINUTES));
			if (historicData != null) {
				for (var historicEntry : historicData.entrySet()) {
					var channelData = historicEntry.getValue();
					if (channelData == null) {
						continue;
					}

					var historicValue = channelData.get(channelAddress);
					if (historicValue == null || historicValue.isJsonNull()) {
						continue;
					}

					var startOfDayValue = JsonUtils.getAsOptionalFloat(historicValue);
					if (startOfDayValue.isPresent()) {
						return startOfDayValue.get();
					}
				}
			}
		} catch (OpenemsNamedException e) {
			this.log.debug("Unable to read historic total energy for [{}]: {}", this.id(), e.getMessage());
		}

		return fallbackValue;
	}

	private void activateScaleFactorLogic() {
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT, SolarEdge.ChannelId.RAW_CURRENT, SolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT_L1, SolarEdge.ChannelId.RAW_CURRENT_L1, SolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT_L2, SolarEdge.ChannelId.RAW_CURRENT_L2, SolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.CURRENT_L3, SolarEdge.ChannelId.RAW_CURRENT_L3, SolarEdge.ChannelId.CURRENT_SF, 3);
		this.addScaleFactorListener(SolarEdge.ChannelId.VOLTAGE_L1_L2, SolarEdge.ChannelId.RAW_VOLTAGE_L1_L2, SolarEdge.ChannelId.VOLTAGE_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.VOLTAGE_L2_L3, SolarEdge.ChannelId.RAW_VOLTAGE_L2_L3, SolarEdge.ChannelId.VOLTAGE_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.VOLTAGE_L3_L1, SolarEdge.ChannelId.RAW_VOLTAGE_L3_L1, SolarEdge.ChannelId.VOLTAGE_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.VOLTAGE_L1, SolarEdge.ChannelId.RAW_VOLTAGE_L1, SolarEdge.ChannelId.VOLTAGE_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.VOLTAGE_L2, SolarEdge.ChannelId.RAW_VOLTAGE_L2, SolarEdge.ChannelId.VOLTAGE_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.VOLTAGE_L3, SolarEdge.ChannelId.RAW_VOLTAGE_L3, SolarEdge.ChannelId.VOLTAGE_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.ACTIVE_POWER, SolarEdge.ChannelId.RAW_ACTIVE_POWER, SolarEdge.ChannelId.ACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.FREQUENCY, SolarEdge.ChannelId.RAW_FREQUENCY, SolarEdge.ChannelId.FREQUENCY_SF, 3);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.APPARENT_POWER, SolarEdge.ChannelId.RAW_APPARENT_POWER, SolarEdge.ChannelId.APPARENT_POWER_SF, 0);
		this.addScaleFactorListener(ElectricityMeter.ChannelId.REACTIVE_POWER, SolarEdge.ChannelId.RAW_REACTIVE_POWER, SolarEdge.ChannelId.REACTIVE_POWER_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.POWER_FACTOR, SolarEdge.ChannelId.RAW_POWER_FACTOR, SolarEdge.ChannelId.POWER_FACTOR_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.ENERGY_PRODUCTION_TOTAL, SolarEdge.ChannelId.RAW_ENERGY_PRODUCTION_TOTAL, SolarEdge.ChannelId.ENERGY_PRODUCTION_TOTAL_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.DC1_CURRENT, SolarEdge.ChannelId.RAW_DC1_CURRENT, SolarEdge.ChannelId.DC1_CURRENT_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.DC1_VOLTAGE, SolarEdge.ChannelId.RAW_DC1_VOLTAGE, SolarEdge.ChannelId.DC1_VOLTAGE_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.DC1_POWER, SolarEdge.ChannelId.RAW_DC1_POWER, SolarEdge.ChannelId.DC1_POWER_SF, 0);
		this.addScaleFactorListener(SolarEdge.ChannelId.TEMPERATURE, SolarEdge.ChannelId.RAW_TEMPERATURE, SolarEdge.ChannelId.TEMPERATURE_SF, 0);
	}

	private void updateMeterConfig() {
		// Meter 1
		if (this.config.enableMeter1()) {
			int baseAddress = 40121;
			switch (this.config.inverterModel()) {
			case TWO_UNIT:
				baseAddress = 40171;
				break;
			case THREE_UNIT:
				baseAddress = 40191;
				break;
			case DEFAULT:
			default:
				baseAddress = 40121;
				break;
			}
			this.createOrUpdateMeter(1, baseAddress);
		} else {
			this.deleteMeter(1);
		}
		
		// Meter 2
		if (this.config.enableMeter2()) {
			int baseAddress = 40295;
			switch (this.config.inverterModel()) {
			case TWO_UNIT:
				baseAddress = 40345;
				break;
			case THREE_UNIT:
				baseAddress = 40365;
				break;
			case DEFAULT:
			default:
				baseAddress = 40295;
				break;
			}
			this.createOrUpdateMeter(2, baseAddress);
		} else {
			this.deleteMeter(2);
		}
		
		// Meter 3
		if (this.config.enableMeter3()) {
			int baseAddress = 40469;
			switch (this.config.inverterModel()) {
			case TWO_UNIT:
				baseAddress = 40519;
				break;
			case THREE_UNIT:
				baseAddress = 40539;
				break;
			case DEFAULT:
			default:
				baseAddress = 40469;
				break;
			}
			this.createOrUpdateMeter(3, baseAddress);
		} else {
			this.deleteMeter(3);
		}
	}

	private String generateMeterId(int meterIndex) {
		String inverterId = this.config.id();
		String indexStr = inverterId.replaceAll("[^0-9]", "");
		String meterId = "meter" + indexStr + "_SolarEdge";
		if (meterIndex > 1) {
			meterId += "_" + meterIndex;
		}
		return meterId;
	}

	private void createOrUpdateMeter(int meterIndex, int baseAddress) {
		try {
			String meterId = this.generateMeterId(meterIndex);
			String factoryPid = "Meter.SolarEdge.Modbus"; // Matches MeterSolarEdgeImpl Component name

			String filter = String.format("(&(%s=%s)(id=%s))", //
					org.osgi.service.cm.ConfigurationAdmin.SERVICE_FACTORYPID, factoryPid, meterId);

			org.osgi.service.cm.Configuration[] configs = this.cm.listConfigurations(filter);

			if (configs == null || configs.length == 0) {
				java.util.Dictionary<String, Object> props = new java.util.Hashtable<>();
				props.put("id", meterId);
				props.put("alias", "SolarEdge Meter " + meterIndex);
				props.put("enabled", true);
				props.put("modbus.id", this.config.modbus_id());
				props.put("modbusUnitId", this.config.modbusUnitId()); // Same Unit ID as Inverter
				props.put("baseAddress", baseAddress);
				props.put("invert", this.config.invert_meter());
				org.osgi.service.cm.Configuration newConfig = this.cm.createFactoryConfiguration(factoryPid, "?");
				newConfig.update(props);
				this.logInfo(this.log, "Created Meter Config: " + meterId);
			} else {
				org.osgi.service.cm.Configuration existingConfig = configs[0];
				java.util.Dictionary<String, Object> props = existingConfig.getProperties();
				boolean updated = false;

				if (!this.config.modbus_id().equals(props.get("modbus.id"))) {
					props.put("modbus.id", this.config.modbus_id());
					updated = true;
				}
				if (props.get("modbusUnitId") == null || !Integer.valueOf(this.config.modbusUnitId()).equals(props.get("modbusUnitId"))) {
					props.put("modbusUnitId", this.config.modbusUnitId());
					updated = true;
				}
				if (props.get("baseAddress") == null || !Integer.valueOf(baseAddress).equals(props.get("baseAddress"))) {
					props.put("baseAddress", baseAddress);
					updated = true;
				}
				if (props.get("invert") == null || !Boolean.valueOf(this.config.invert_meter()).equals(props.get("invert"))) {
					props.put("invert", this.config.invert_meter());
					updated = true;
				}

				if (updated) {
					existingConfig.update(props);
					this.logInfo(this.log, "Updated Meter Config: " + meterId);
				}
			}
		} catch (Exception e) {
			this.logError(this.log, "Failed to create/update Meter Config: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void deleteMeter(int meterIndex) {
		try {
			String meterId = this.generateMeterId(meterIndex);
			String factoryPid = "Meter.SolarEdge.Modbus";

			String filter = String.format("(&(%s=%s)(id=%s))", //
					org.osgi.service.cm.ConfigurationAdmin.SERVICE_FACTORYPID, factoryPid, meterId);

			org.osgi.service.cm.Configuration[] configs = this.cm.listConfigurations(filter);

			if (configs != null && configs.length > 0) {
				for (org.osgi.service.cm.Configuration config : configs) {
					config.delete();
					this.logInfo(this.log, "Deleted Meter Config: " + meterId);
				}
			}
		} catch (Exception e) {
			this.logError(this.log, "Failed to delete Meter Config: " + e.getMessage());
			e.printStackTrace();
		}
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

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
			try {
				this.setPvLimitHandler.run();
			} catch (OpenemsNamedException e) {
				this.logError(this.log, "Unable to set PV Limit: " + e.getMessage());
			}
			break;
		}
	}

	@Override
	public String debugLog() {
		return "Hello World";
	}
}
