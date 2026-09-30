package io.openems.edge.meter.artel;

//import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.INVERT_IF_TRUE;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Artel.MDM3100", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class MeterArtelMDM3100Impl extends AbstractOpenemsModbusComponent
		implements MeterArtelMDM3100, ElectricityMeter, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	private Config Config;

	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public MeterArtelMDM3100Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				MeterArtelMDM3100.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config Config) throws OpenemsException {
		this.Config = Config;
		if (super.activate(context, Config.id(), Config.alias(), Config.enabled(), Config.modbusUnitId(), this.cm,
				"Modbus", Config.modbus_id())) {
			return;
		}
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
		this.Config = null;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		ModbusProtocol protocol = new ModbusProtocol(this,

				new FC3ReadRegistersTask(0x0300, Priority.HIGH,
						// ==== VOLTAGES ====
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(0x0300),
								SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(0x0301),
								SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(0x0302),
								SCALE_FACTOR_2),
						m(ElectricityMeter.ChannelId.VOLTAGE, new UnsignedWordElement(0x0303),
								SCALE_FACTOR_2),
						new DummyRegisterElement(0x304, 0x307),
						// ==== CURRENTS ====
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(0x0308)),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(0x0309)),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(0x030A)),
						m(ElectricityMeter.ChannelId.CURRENT, new UnsignedWordElement(0x030B)),
						new DummyRegisterElement(0x30C, 0x30F),
						// ==== ACTIVE POWER ====
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new UnsignedWordElement(0x0310),
								INVERT_IF_TRUE(this.Config.invert())),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new UnsignedWordElement(0x0311),
								INVERT_IF_TRUE(this.Config.invert())),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new UnsignedWordElement(0x0312),
								INVERT_IF_TRUE(this.Config.invert())),
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new UnsignedWordElement(0x0313),
								INVERT_IF_TRUE(this.Config.invert())),

						// ==== REACTIVE POWER ====
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, new UnsignedWordElement(0x0314)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, new UnsignedWordElement(0x0315)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, new UnsignedWordElement(0x0316)),
						m(ElectricityMeter.ChannelId.REACTIVE_POWER, new UnsignedWordElement(0x0317)),

						new DummyRegisterElement(0x318, 0x31B),
						// ==== FREQUENCY ====
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(0x031C))),

				// ==== ENERGY REGISTERS ====
				new FC3ReadRegistersTask(0x0500, Priority.LOW,
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(0x0500).wordOrder(WordOrder.MSWLSW)),
						m(MeterArtelMDM3100.ChannelId.IMP_ACTIVEPOWER,
								new UnsignedDoublewordElement(0x0502).wordOrder(WordOrder.MSWLSW)),
						m(MeterArtelMDM3100.ChannelId.EXP_ACTIVEPOWER,
								new UnsignedDoublewordElement(0x0504).wordOrder(WordOrder.MSWLSW)),
						m(MeterArtelMDM3100.ChannelId.REACTIVE_TOTAL,
								new UnsignedDoublewordElement(0x0506).wordOrder(WordOrder.MSWLSW))

				));

		return protocol;
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public MeterType getMeterType() {
		return this.Config.type();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode) //
		);
	}
}
