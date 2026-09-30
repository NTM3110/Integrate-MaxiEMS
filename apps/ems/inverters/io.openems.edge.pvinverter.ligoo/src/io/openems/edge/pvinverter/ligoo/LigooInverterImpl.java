package io.openems.edge.pvinverter.ligoo;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.common.types.OpenemsType;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.*;
import io.openems.edge.bridge.modbus.api.element.*;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.device.api.DeviceInformation;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.*;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.*;

@Designate(ocd = Config.class, factory = true)
@Component(
		name = "pvInverter.Ligoo",
		immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE)

public class LigooInverterImpl extends AbstractOpenemsModbusComponent
		implements LigooInverter, ManagedSymmetricPvInverter, ElectricityMeter, DeviceInformation, OpenemsComponent, ModbusComponent {


	@Reference
	private ConfigurationAdmin cm;

	protected Config config;

	public LigooInverterImpl() {
		super(
				OpenemsComponent.ChannelId.values(),
				ModbusComponent.ChannelId.values(),
				ElectricityMeter.ChannelId.values(),
				DeviceInformation.ChannelId.values(),
				LigooInverter.ChannelId.values(),
				HybridInverter.ChannelId.values()
		);
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
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
//				new FC3ReadRegistersTask(0, Priority.LOW,
//						m(DeviceInformation.ChannelId.HARDWARE_VERSION,
//								new StringWordElement(0, 1)),
//						m(DeviceInformation.ChannelId.FIRMWARE_VERSION,
//								new StringWordElement(1,2))),
//				new FC3ReadRegistersTask(45,Priority.LOW,
//						m(DeviceInformation.ChannelId.SERIAL_NUMBER,
//								new StringWordElement(45,16)),
//						m(DeviceInformation.ChannelId.DEVICE_NAME,
//								new StringWordElement(51,16)),
//						m(DeviceInformation.ChannelId.MANUFACTURER,
//								new StringWordElement(67,20))),
				new FC3ReadRegistersTask(12672,Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY,
								new UnsignedDoublewordElement(12672), SCALE_FACTOR_2),
						new DummyRegisterElement(12674,12685),
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(12686), SCALE_FACTOR_2)),
				new FC3ReadRegistersTask(12608, Priority.HIGH,
						m(HybridInverter.ChannelId.BATTERY_VOLTAGE,
								new UnsignedWordElement(12608), SCALE_FACTOR_2),
						m(HybridInverter.ChannelId.BATTERY_CURRENT,
								new UnsignedWordElement(12609), SCALE_FACTOR_2),
						new DummyRegisterElement(12610,12612),
						m(HybridInverter.ChannelId.BATTERY_SOC,
								new UnsignedWordElement(12613)),
						m(HybridInverter.ChannelId.BATTERY_TEMPURATURE,
								new SignedWordElement(12614), SCALE_FACTOR_MINUS_1),
						m(HybridInverter.ChannelId.BATTERY_CHARGE_VOLTAGE,
								new UnsignedWordElement(12615), SCALE_FACTOR_2),
						m(HybridInverter.ChannelId.BATTERY_CHARGE_CURRENT,
								new UnsignedWordElement(12616), SCALE_FACTOR_2),
						m(HybridInverter.ChannelId.BATTERY_DISCHARGE_VOLTAGE,
								new UnsignedWordElement(12617), SCALE_FACTOR_2),
						m(HybridInverter.ChannelId.BATTERY_DISCHARGE_CURRENT,
								new UnsignedWordElement(12618), SCALE_FACTOR_2),
						m(HybridInverter.ChannelId.BATTERY_POWER,
								new SignedDoublewordElement(12619)),
						m(HybridInverter.ChannelId.BATTERY_CAPACITY,
								new UnsignedWordElement(12621)),
						m(HybridInverter.ChannelId.BATTERY_STATE,
								new UnsignedWordElement(12622))),
				new FC3ReadRegistersTask(20489,Priority.HIGH,
						m(ElectricityMeter.ChannelId.FREQUENCY,
								new UnsignedWordElement(20489), SCALE_FACTOR_1),
						new DummyRegisterElement(20490,20491),
						m(ElectricityMeter.ChannelId.VOLTAGE,
								new UnsignedWordElement(20492), SCALE_FACTOR_2),
						new DummyRegisterElement(20493,20494),
						m(ElectricityMeter.ChannelId.CURRENT,
								new UnsignedWordElement(20495), SCALE_FACTOR_2)),
				new FC3ReadRegistersTask(20526,Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER,
								new SignedDoublewordElement(20526)))
		);
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

}

