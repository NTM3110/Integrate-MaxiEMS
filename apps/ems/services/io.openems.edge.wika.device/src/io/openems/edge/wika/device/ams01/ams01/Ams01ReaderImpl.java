package io.openems.edge.wika.device.ams01.ams01;

import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.wika.api.WikaManagement;
import io.openems.edge.wika.api.WikaAms01Alarm;
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

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
			name = "wika.ams01",//
			immediate = true, //
			configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class Ams01ReaderImpl extends AbstractOpenemsModbusComponent
		implements  Ams01Reader, ModbusComponent, OpenemsComponent {

		@Reference
		private ConfigurationAdmin cm;

		@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
		protected void setModbus(BridgeModbus modbus) {
			super.setModbus(modbus);
		}

		private Config config = null;

		public Ams01ReaderImpl() {
			super(
			OpenemsComponent.ChannelId.values(), //
			ModbusComponent.ChannelId.values(), //
			Ams01Reader.ChannelId.values(), //
			WikaAms01Alarm.ChannelId.values(), //
			WikaManagement.ChannelId.values() //
			);
		}

		@Activate
		private void activate(ComponentContext context, Config config) throws OpenemsException {
			if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,"Modbus", config.modbus_id())) {
				return;
			}
			this.config = config;
		}

		@Override
		@Deactivate
		protected void deactivate() {
			super.deactivate();
		}

		@Override
		protected ModbusProtocol defineModbusProtocol() {
			return new ModbusProtocol(this,
				// --- Task 1: Node Temperature Sensors (Registers 0-44) ---
				new FC4ReadInputRegistersTask(0, Priority.HIGH,
					m(Ams01Reader.ChannelId.IN_TEMP_A, new SignedWordElement(0), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_TEMP_B, new SignedWordElement(1), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_TEMP_C, new SignedWordElement(2), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(9, Priority.HIGH,
					m(Ams01Reader.ChannelId.OUT_TEMP_A, new SignedWordElement(9), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_TEMP_B, new SignedWordElement(10), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_TEMP_C, new SignedWordElement(11), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(18, Priority.HIGH,
					m(Ams01Reader.ChannelId.MBA_TEMP_A, new SignedWordElement(18), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_TEMP_B, new SignedWordElement(19), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_TEMP_C, new SignedWordElement(20), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),

				// --- Task 2: Environmental Sensors (Registers 135-137) ---
				new FC4ReadInputRegistersTask(135, Priority.HIGH,
					m(Ams01Reader.ChannelId.ENVIRONMENTAL_TEMPERATURE, new SignedWordElement(135), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.ENVIRONMENTAL_HUMIDITY, new UnsignedWordElement(136), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.ENVIRONMENTAL_DEWPOINT, new SignedWordElement(137), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),

				// --- Task 3: PD Data (Registers 999-1039) ---
				new FC4ReadInputRegistersTask(999, Priority.HIGH,
					m(Ams01Reader.ChannelId.IN_PD_CODE, new UnsignedWordElement(999)),
					m(Ams01Reader.ChannelId.IN_RATIO, new UnsignedWordElement(1000), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_EPPC, new UnsignedWordElement(1001), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_PD_LEVEL, new UnsignedDoublewordElement(1002), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1008, Priority.HIGH,
					m(Ams01Reader.ChannelId.OUT_PD_CODE, new UnsignedWordElement(1008)),
					m(Ams01Reader.ChannelId.OUT_RATIO, new UnsignedWordElement(1009), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_EPPC, new UnsignedWordElement(1010), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_PD_LEVEL, new UnsignedDoublewordElement(1011), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1017, Priority.HIGH,
					m(Ams01Reader.ChannelId.MBA_PD_CODE, new UnsignedWordElement(1017)),
					m(Ams01Reader.ChannelId.MBA_RATIO, new UnsignedWordElement(1018), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_EPPC, new UnsignedWordElement(1019), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_PD_LEVEL, new UnsignedDoublewordElement(1020), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1044, Priority.HIGH,
					m(Ams01Reader.ChannelId.IN_PD_PRESENCE_CODE, new UnsignedWordElement(1044)),
					m(Ams01Reader.ChannelId.IN_PRESENCE_RATIO, new UnsignedWordElement(1045), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_PRESENCE_EPPC, new UnsignedWordElement(1046), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_PRESENCE_PD_LEVEL, new UnsignedDoublewordElement(1047), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1053, Priority.HIGH,
					m(Ams01Reader.ChannelId.OUT_PD_PRESENCE_CODE, new UnsignedWordElement(1053)),
					m(Ams01Reader.ChannelId.OUT_PRESENCE_RATIO, new UnsignedWordElement(1054), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_PRESENCE_EPPC, new UnsignedWordElement(1055), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_PD_PRESENCE_LEVEL, new UnsignedDoublewordElement(1056), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1062, Priority.HIGH,
					m(Ams01Reader.ChannelId.MBA_PD_PRESENCE_CODE, new UnsignedWordElement(1062)),
					m(Ams01Reader.ChannelId.MBA_PRESENCE_RATIO, new UnsignedWordElement(1063), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_PRESENCE_EPPC, new UnsignedWordElement(1064), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_PD_PRESENCE_LEVEL, new UnsignedDoublewordElement(1065), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1089, Priority.HIGH,
					m(Ams01Reader.ChannelId.IN_PD_EARLY_CODE, new UnsignedWordElement(1089)),
					m(Ams01Reader.ChannelId.IN_EARLY_RATIO, new UnsignedWordElement(1090), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_EARLY_EPPC, new UnsignedWordElement(1091), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.IN_PD_EARLY_LEVEL, new UnsignedDoublewordElement(1092), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1098, Priority.HIGH,
					m(Ams01Reader.ChannelId.OUT_PD_EARLY_CODE, new UnsignedWordElement(1098)),
					m(Ams01Reader.ChannelId.OUT_EARLY_RATIO, new UnsignedWordElement(1099), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_EARLY_EPPC, new UnsignedWordElement(1100), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.OUT_PD_EARLY_LEVEL, new UnsignedDoublewordElement(1101), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(1107, Priority.HIGH,
					m(Ams01Reader.ChannelId.MBA_PD_EARLY_CODE, new UnsignedWordElement(1107)),
					m(Ams01Reader.ChannelId.MBA_EARLY_RATIO, new UnsignedWordElement(1108), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_EARLY_EPPC, new UnsignedWordElement(1109), ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(Ams01Reader.ChannelId.MBA_PD_EARLY_LEVEL, new UnsignedDoublewordElement(1110), ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(32001, Priority.HIGH,
					m(WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_FLAG, new UnsignedWordElement(32001)),
					m(WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_VALUE, new UnsignedWordElement(32002),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(WikaAms01Alarm.ChannelId.OUT_ALARM_TEMP_FLAG, new UnsignedWordElement(32003)),
					m(WikaAms01Alarm.ChannelId.OUT_ALARM_TEMP_VALUE, new UnsignedWordElement(32004),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
					m(WikaAms01Alarm.ChannelId.MBA_ALARM_TEMP_FLAG, new UnsignedWordElement(32005)),
					m(WikaAms01Alarm.ChannelId.MBA_ALARM_TEMP_VALUE, new UnsignedWordElement(32006),
							ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC4ReadInputRegistersTask(33001, Priority.HIGH,
					m(WikaAms01Alarm.ChannelId.IN_ALARM_PD_FLAG, new UnsignedWordElement(33001)),
					m(WikaAms01Alarm.ChannelId.OUT_ALARM_PD_FLAG, new UnsignedWordElement(33002)),
					m(WikaAms01Alarm.ChannelId.MBA_ALARM_PD_FLAG, new UnsignedWordElement(33003))
		));
		}
		@Override
		public String debugLog() {
			return "T:" + this.channel(Ams01Reader.ChannelId.ENVIRONMENTAL_TEMPERATURE).value().asString();
		}
}
