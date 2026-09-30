package io.openems.edge.moxa.model.e1240;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.CoilElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.task.FC1ReadCoilsTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.device.api.DeviceInformation;
import io.openems.edge.moxa.api.MoxaRegister;
import io.openems.edge.moxa.model.e1240.MoxaE1240;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

@Designate(ocd = io.openems.edge.moxa.model.e1240.Config.class, factory = true)
@Component(//
        name = "Moxa.E1240", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class MoxaE1240Impl extends AbstractOpenemsModbusComponent //
        implements MoxaE1240, DeviceInformation, ModbusComponent, OpenemsComponent {

    @Reference
    private ConfigurationAdmin cm;

    @Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    public io.openems.edge.moxa.model.e1240.Config config;

	public MoxaE1240Impl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				MoxaE1240.ChannelId.values(), //
				DeviceInformation.ChannelId.values(), //
				MoxaRegister.ChannelId.values());
	}

    @Activate
    @Modified
    protected void activate(final ComponentContext context, final io.openems.edge.moxa.model.e1240.Config config) throws OpenemsException {
        this.config = config;

        super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
                config.modbus_id());
    }

    @Deactivate
    @Override
    protected void deactivate() {
        super.deactivate();
    }

	@Override
	public String debugLog() {
		return "";
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC4ReadInputRegistersTask(30061, Priority.HIGH,
						m(MoxaRegister.ChannelId.AI_01, new SignedWordElement(30061)),
						m(MoxaRegister.ChannelId.AI_02, new SignedWordElement(30062)),
						m(MoxaRegister.ChannelId.AI_03, new SignedWordElement(30063)),
						m(MoxaRegister.ChannelId.AI_04, new SignedWordElement(30064)),
						m(MoxaRegister.ChannelId.AI_05, new SignedWordElement(30065)),
						m(MoxaRegister.ChannelId.AI_06, new SignedWordElement(30066)),
						m(MoxaRegister.ChannelId.AI_07, new SignedWordElement(30067)),
						m(MoxaRegister.ChannelId.AI_08, new SignedWordElement(30068))),
				new FC4ReadInputRegistersTask(35001, Priority.LOW, //
						m(DeviceInformation.ChannelId.MODEL,
								new StringWordElement(35001, 10)),
						new DummyRegisterElement(35011, 35020),
						m(DeviceInformation.ChannelId.DEVICE_UPTIME,
								new StringWordElement(35021, 2)),
						new DummyRegisterElement(35023, 35029),
						m(DeviceInformation.ChannelId.FIRMWARE_VERSION,
								new StringWordElement(35030, 2)),
						m(DeviceInformation.ChannelId.FIRMWARE_BUILD_DATE,
								new StringWordElement(35032, 2)),
						new DummyRegisterElement(35034, 35040),
						m(DeviceInformation.ChannelId.DEVICE_NAME,
								new StringWordElement(35041, 30)))
		);
	}

	static String parseFirmwareVersion(Object value) {
		if (!(value instanceof String version) || version.length() < 3) {
			return null;
		}
		return "V%d.%d.%d".formatted(//
				(int) version.charAt(0), //
				(int) version.charAt(1), //
				(int) version.charAt(2));
	}
}
