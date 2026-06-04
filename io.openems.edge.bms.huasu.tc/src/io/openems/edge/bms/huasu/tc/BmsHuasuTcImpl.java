package io.openems.edge.bms.huasu.tc;

import io.openems.common.exceptions.OpenemsError;
import io.openems.edge.common.startstop.StartStop;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "BMS.Huasu.TC", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class BmsHuasuTcImpl extends AbstractOpenemsModbusComponent
		implements BmsHuasuTc, ModbusComponent, OpenemsComponent {

	@Reference
	private ConfigurationAdmin cm;

	public BmsHuasuTcImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				BmsHuasuTc.ChannelId.values() //
		);
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbusId())) {
			return;
		}
	}

	@Modified
	private void modified(ComponentContext context, Config config) throws OpenemsException {
		if (super.modified(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbusId())) {
			return;
		}
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private static final ElementToChannelConverter SIGNED_MAGNITUDE_CONVERTER = new ElementToChannelConverter(
			// elementToChannel
			value -> {
				if (value == null) {
					return null;
				}
				int intValue = (Integer) value;
				// check the highest bit (15th bit)
				boolean isNegative = (intValue & 0x8000) != 0;
				// mask out the sign bit
				int magnitude = intValue & 0x7FFF;
				return isNegative ? -magnitude : magnitude;
			},
			// channelToElement
			value -> {
				if (value == null) {
					return null;
				}
				int intValue = (Integer) value;
				if (intValue < 0) {
					return Math.abs(intValue) | 0x8000;
				}
				return intValue;
			});

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				new FC3ReadRegistersTask(0x0003, Priority.HIGH,
						this.m(BmsHuasuTc.ChannelId.STRING_CURRENT, new UnsignedWordElement(0x0003),
								ElementToChannelConverter.chain(SIGNED_MAGNITUDE_CONVERTER,
										ElementToChannelConverter.DIRECT_1_TO_1)),
						this.m(BmsHuasuTc.ChannelId.AMBIENT_TEMPERATURE, new UnsignedWordElement(0x0004),
								ElementToChannelConverter.chain(SIGNED_MAGNITUDE_CONVERTER,
										ElementToChannelConverter.DIRECT_1_TO_1))
				)
			);
	}

	// @Override
	// public void setStartStop(StartStop value) throws OpenemsError.OpenemsNamedException {

	// }
}