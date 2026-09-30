package io.openems.edge.pvinverter.solis.ongrid;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;

import java.util.ArrayList;

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

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.ModbusElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import io.openems.edge.pvinverter.api.RtsPvInverterAlarmId;
import io.openems.edge.pvinverter.api.RtsPvInverterAlarms;
import io.openems.edge.pvinverter.api.RtsPvInverterChannelIdFactory;
import io.openems.edge.pvinverter.api.RtsPvInverterUtils;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "PV-Inverter.Solis.OnGrid", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { "type=PRODUCTION" })
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class PvInverterSolisOnGridImpl extends AbstractOpenemsModbusComponent
		implements PvInverterSolisOnGrid, ManagedSymmetricPvInverter, RtsPvInverter, RtsPvInverterAlarms,
		ElectricityMeter, ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	private final SetPvLimitHandler setPvLimitHandler = new SetPvLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT);
	private final SetReactivePowerLimitHandler setReactivePowerLimitHandler = new SetReactivePowerLimitHandler(this,
			ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT);

	@Reference
	private ConfigurationAdmin cm;

	private Config config;
	private boolean dcInputTaskInitialized;
	private int initializedDcInputCount;

	int getConfiguredMaxActivePower() {
		return this.config == null ? 0 : this.config.maxActivePower();
	}

	public PvInverterSolisOnGridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				RtsPvInverter.ChannelId.values(), //
				RtsPvInverterAlarms.ChannelId.values(), //
				PvInverterSolisOnGrid.ChannelId.values());
		this.initializeRtsPvInverterAlarms(SolisOnGridFaultMapper.SUPPORTED_ALARM_MASK);
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
		this.config = config;
		this._setMaxActivePower(config.maxActivePower());
		this._setMaxApparentPower(config.maxActivePower());
		this._setAlarm(RtsPvInverterAlarmId.CONFIGURATION_ERROR, config.maxActivePower() <= 0);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				// Product and capability metadata. Documented 3000..3004 -> PDU 2999..3003.
				new FC4ReadInputRegistersTask(2999, Priority.LOW,
						m(PvInverterSolisOnGrid.ChannelId.PRODUCT_MODEL, new UnsignedWordElement(2999)),
						m(PvInverterSolisOnGrid.ChannelId.DSP_SOFTWARE_VERSION, new UnsignedWordElement(3000)),
						m(PvInverterSolisOnGrid.ChannelId.LCD_SOFTWARE_VERSION, new UnsignedWordElement(3001)),
						m(PvInverterSolisOnGrid.ChannelId.AC_OUTPUT_TYPE, new UnsignedWordElement(3002)),
						m(PvInverterSolisOnGrid.ChannelId.DC_INPUT_TYPE, new UnsignedWordElement(3003))),

				// Power and energy. Solis total energy is kWh; RTS energy is Wh.
				new FC4ReadInputRegistersTask(3004, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new UnsignedDoublewordElement(3004).wordOrder(WordOrder.MSWLSW)),
						m(RtsPvInverter.ChannelId.DC_POWER,
								new UnsignedDoublewordElement(3006).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(3008).wordOrder(WordOrder.MSWLSW), SCALE_FACTOR_3),
						new DummyRegisterElement(3010, 3013),
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY,
								new UnsignedWordElement(3014), SCALE_FACTOR_2)),

				// AC monitoring. Per-phase P/Q/S is not exposed by this protocol.
				new FC4ReadInputRegistersTask(3033, Priority.HIGH,
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(3033), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(3034), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(3035), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(3036), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(3037), SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(3038), SCALE_FACTOR_2),
						new DummyRegisterElement(3039, 3040),
						m(PvInverterSolisOnGrid.ChannelId.TEMPERATURE, new UnsignedWordElement(3041)),
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(3042), SCALE_FACTOR_1),
						m(PvInverterSolisOnGrid.ChannelId.INVERTER_STATUS, new UnsignedWordElement(3043)),
						new DummyRegisterElement(3044, 3054),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER,
								new SignedDoublewordElement(3055).wordOrder(WordOrder.MSWLSW)),
						m(ElectricityMeter.ChannelId.APPARENT_POWER,
								new SignedDoublewordElement(3057).wordOrder(WordOrder.MSWLSW))),

				// Fault words and working status. Documented 3067..3072 -> PDU 3066..3071.
				new FC4ReadInputRegistersTask(3066, Priority.HIGH,
						m(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_01, new UnsignedWordElement(3066)),
						m(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_02, new UnsignedWordElement(3067)),
						m(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_03, new UnsignedWordElement(3068)),
						m(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_04, new UnsignedWordElement(3069)),
						new DummyRegisterElement(3070, 3070),
						m(PvInverterSolisOnGrid.ChannelId.WORKING_STATUS, new UnsignedWordElement(3071))),

				// Device Type is explicitly documented without the normal minus-one offset.
				new FC4ReadInputRegistersTask(35000, Priority.LOW,
						m(PvInverterSolisOnGrid.ChannelId.DEVICE_TYPE, new UnsignedWordElement(35000))),

				// Independent FC06 writes avoid touching the reserved register between P and Q.
				new FC6WriteRegisterTask(3069,
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL, new UnsignedWordElement(3069))),
				new FC6WriteRegisterTask(3070,
						m(ManagedSymmetricPvInverter.ChannelId.REMOTE_CONTROL_Q, new UnsignedWordElement(3070))),
				new FC6WriteRegisterTask(3080,
						m(PvInverterSolisOnGrid.ChannelId.P_LIMIT_RAW, new SignedWordElement(3080))),
				new FC6WriteRegisterTask(3082,
						m(ManagedSymmetricPvInverter.ChannelId.REACTIVE_POWER_LIMIT, new SignedWordElement(3082))));
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
			this.executeWriteCycle();
			break;
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.updateAfterProcessImage();
			break;
		}
	}

	private void executeWriteCycle() {
		try {
			this.setPvLimitHandler.run();
			this.setReactivePowerLimitHandler.run();
			this.channel(PvInverterSolisOnGrid.ChannelId.PV_LIMIT_FAILED).setNextValue(false);
		} catch (OpenemsNamedException e) {
			this.channel(PvInverterSolisOnGrid.ChannelId.PV_LIMIT_FAILED).setNextValue(true);
		}
	}

	private void updateAfterProcessImage() {
		var invalidData = this.initializeDcInputChannels();
		invalidData |= this.updateDerivedChannels();
		this.updateAlarms(invalidData);
	}

	private boolean initializeDcInputChannels() {
		var dcInputType = this.getInteger(PvInverterSolisOnGrid.ChannelId.DC_INPUT_TYPE);
		if (dcInputType == null) {
			return false;
		}
		if (dcInputType < 0 || dcInputType > 19) {
			this._setDcInputCount(null);
			return true;
		}

		var dcInputCount = dcInputType + 1;
		this._setDcInputCount(dcInputCount);
		if (dcInputCount > 4 || this.dcInputTaskInitialized) {
			return false;
		}

		var elements = new ArrayList<ModbusElement>(dcInputCount * 2);
		for (var input = 1; input <= dcInputCount; input++) {
			var voltageChannelId = RtsPvInverterChannelIdFactory.dcVoltage(input);
			var currentChannelId = RtsPvInverterChannelIdFactory.dcCurrent(input);
			var powerChannelId = RtsPvInverterChannelIdFactory.dcPower(input);
			this.addChannel(powerChannelId);
			var address = 3021 + (input - 1) * 2;
			elements.add(m(voltageChannelId, new UnsignedWordElement(address), SCALE_FACTOR_2));
			elements.add(m(currentChannelId, new UnsignedWordElement(address + 1), SCALE_FACTOR_2));
		}
		this.getModbusProtocol().addTask(new FC4ReadInputRegistersTask(3021, Priority.HIGH,
				elements.toArray(ModbusElement[]::new)));
		this.initializedDcInputCount = dcInputCount;
		this.dcInputTaskInitialized = true;
		return false;
	}

	private boolean updateDerivedChannels() {
		var invalidData = false;
		if (this.dcInputTaskInitialized) {
			var dcVoltages = new Integer[this.initializedDcInputCount];
			var dcCurrents = new Integer[this.initializedDcInputCount];
			var dcPowers = new Integer[this.initializedDcInputCount];
			for (var input = 1; input <= this.initializedDcInputCount; input++) {
				var voltage = this.getInteger(RtsPvInverterChannelIdFactory.dcVoltage(input));
				var current = this.getInteger(RtsPvInverterChannelIdFactory.dcCurrent(input));
				var power = RtsPvInverterUtils.calculateDcPower(voltage, current);
				this.channel(RtsPvInverterChannelIdFactory.dcPower(input)).setNextValue(power.value());
				dcVoltages[input - 1] = voltage;
				dcCurrents[input - 1] = current;
				dcPowers[input - 1] = power.value();
				invalidData |= power.isInvalid();
			}
			var dcVoltage = RtsPvInverterUtils.averageAvailable(dcVoltages);
			this._setDcVoltage(dcVoltage.value());
			invalidData |= dcVoltage.isInvalid();

			var dcCurrent = RtsPvInverterUtils.sumAvailable(dcCurrents);
			this._setDcCurrent(dcCurrent.value());
			invalidData |= dcCurrent.isInvalid();

			if (this.getDcPower().get() == null) {
				var dcPower = RtsPvInverterUtils.sumAvailable(dcPowers);
				this._setDcPower(dcPower.value());
				invalidData |= dcPower.isInvalid();
			}
		}

		var voltage = RtsPvInverterUtils.averageAvailable(this.getInteger(ElectricityMeter.ChannelId.VOLTAGE_L1),
				this.getInteger(ElectricityMeter.ChannelId.VOLTAGE_L2),
				this.getInteger(ElectricityMeter.ChannelId.VOLTAGE_L3));
		this.channel(ElectricityMeter.ChannelId.VOLTAGE).setNextValue(voltage.value());
		invalidData |= voltage.isInvalid();

		var current = RtsPvInverterUtils.sumAvailable(this.getInteger(ElectricityMeter.ChannelId.CURRENT_L1),
				this.getInteger(ElectricityMeter.ChannelId.CURRENT_L2),
				this.getInteger(ElectricityMeter.ChannelId.CURRENT_L3));
		this.channel(ElectricityMeter.ChannelId.CURRENT).setNextValue(current.value());
		invalidData |= current.isInvalid();

		var powerFactor = RtsPvInverterUtils.calculatePowerFactor(
				this.getInteger(ElectricityMeter.ChannelId.ACTIVE_POWER),
				this.getInteger(ElectricityMeter.ChannelId.REACTIVE_POWER),
				this.getInteger(ElectricityMeter.ChannelId.APPARENT_POWER));
		this._setPowerFactor(powerFactor.value());
		invalidData |= powerFactor.isInvalid();
		return invalidData;
	}

	private void updateAlarms(boolean calculationInvalid) {
		var fault01 = this.getInteger(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_01);
		var fault02 = this.getInteger(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_02);
		var fault03 = this.getInteger(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_03);
		var fault04 = this.getInteger(PvInverterSolisOnGrid.ChannelId.FAULT_CODE_04);
		var deviceType = this.getInteger(PvInverterSolisOnGrid.ChannelId.DEVICE_TYPE);
		var acOutputType = this.getInteger(PvInverterSolisOnGrid.ChannelId.AC_OUTPUT_TYPE);
		var dcInputType = this.getInteger(PvInverterSolisOnGrid.ChannelId.DC_INPUT_TYPE);

		if (fault01 == null || fault02 == null || fault03 == null || fault04 == null || deviceType == null
				|| acOutputType == null || dcInputType == null) {
			this.invalidateRtsPvInverterAlarmData();
			if (!this.getModbusCommunicationFailed()) {
				this._setAlarm(RtsPvInverterAlarmId.INVALID_DATA, true);
			}
			return;
		}

		var metadataInvalid = acOutputType < 0 || acOutputType > 3 || dcInputType < 0 || dcInputType > 19;
		var unsupportedDevice = !isSupportedDeviceType(deviceType) || dcInputType >= 4;
		var configurationError = this.config == null || this.config.maxActivePower() <= 0;
		var snapshot = SolisOnGridFaultMapper.map(fault01, fault02, fault03, fault04,
				calculationInvalid || metadataInvalid, configurationError, unsupportedDevice);
		this.applyRtsPvInverterAlarmSnapshot(SolisOnGridFaultMapper.SUPPORTED_ALARM_MASK, snapshot);
	}

	private Integer getInteger(io.openems.edge.common.channel.ChannelId channelId) {
		IntegerReadChannel channel = this.channel(channelId);
		return channel.value().get();
	}

	static boolean isSupportedDeviceType(int deviceType) {
		return deviceType == 0x1010 || deviceType == 0x1020;
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString() + "|S:"
				+ this.channel(PvInverterSolisOnGrid.ChannelId.INVERTER_STATUS).value().asString();
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
}
