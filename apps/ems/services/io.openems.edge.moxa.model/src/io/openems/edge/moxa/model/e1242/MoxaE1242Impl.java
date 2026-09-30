package io.openems.edge.moxa.model.e1242;

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
import io.openems.edge.bridge.modbus.api.task.FC2ReadInputsTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.device.api.DeviceInformation;
import io.openems.edge.moxa.api.MoxaRegister;
import io.openems.edge.moxa.model.e1242.MoxaE1242;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

@Designate(ocd = io.openems.edge.moxa.model.e1242.Config.class, factory = true)
@Component(//
        name = "Moxa.E1242", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class MoxaE1242Impl extends AbstractOpenemsModbusComponent //
        implements MoxaE1242, DeviceInformation, ModbusComponent, OpenemsComponent {

    @Reference
    private ConfigurationAdmin cm;

    @Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    public io.openems.edge.moxa.model.e1242.Config config;

	public MoxaE1242Impl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				MoxaE1242.ChannelId.values(), //
				DeviceInformation.ChannelId.values(), //
				MoxaRegister.ChannelId.values());
	}

    @Activate
    @Modified
    protected void activate(final ComponentContext context, final io.openems.edge.moxa.model.e1242.Config config) throws OpenemsException {
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
				new FC1ReadCoilsTask(1, Priority.HIGH,
						m(MoxaRegister.ChannelId.DO_01, new CoilElement(1)),
						m(MoxaRegister.ChannelId.DO_02, new CoilElement(2)),
						m(MoxaRegister.ChannelId.DO_03, new CoilElement(3)),
						m(MoxaRegister.ChannelId.DO_04, new CoilElement(4))),
				new FC2ReadInputsTask(10001, Priority.HIGH,
						m(MoxaRegister.ChannelId.DI_01, new CoilElement(10001)),
						m(MoxaRegister.ChannelId.DI_02, new CoilElement(10002)),
						m(MoxaRegister.ChannelId.DI_03, new CoilElement(10003)),
						m(MoxaRegister.ChannelId.DI_04, new CoilElement(10004)),
						m(MoxaRegister.ChannelId.DI_05, new CoilElement(10005)),
						m(MoxaRegister.ChannelId.DI_06, new CoilElement(10006)),
						m(MoxaRegister.ChannelId.DI_07, new CoilElement(10007)),
						m(MoxaRegister.ChannelId.DI_08, new CoilElement(10008))),
				new FC4ReadInputRegistersTask(30578, Priority.HIGH,
						m(MoxaRegister.ChannelId.AI_01, new SignedWordElement(30578)),
						m(MoxaRegister.ChannelId.AI_02, new SignedWordElement(30579)),
						m(MoxaRegister.ChannelId.AI_03, new SignedWordElement(30580)),
						m(MoxaRegister.ChannelId.AI_04, new SignedWordElement(30581))),
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
