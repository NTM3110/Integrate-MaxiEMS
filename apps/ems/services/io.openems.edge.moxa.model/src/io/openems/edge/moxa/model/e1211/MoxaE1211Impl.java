package io.openems.edge.moxa.model.e1211;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.*;
import io.openems.edge.bridge.modbus.api.element.CoilElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC1ReadCoilsTask;
import io.openems.edge.bridge.modbus.api.task.FC2ReadInputsTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.device.api.DeviceInformation;
import io.openems.edge.moxa.api.MoxaRegister;
import io.openems.edge.moxa.model.e1211.MoxaE1211;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

@Designate(ocd = io.openems.edge.moxa.model.e1211.Config.class, factory = true)
@Component(//
        name = "Moxa.E1211", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class MoxaE1211Impl extends AbstractOpenemsModbusComponent //
        implements MoxaE1211, DeviceInformation, ModbusComponent, OpenemsComponent {

    @Reference
    private ConfigurationAdmin cm;

    @Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    public io.openems.edge.moxa.model.e1211.Config config;

	public MoxaE1211Impl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				MoxaE1211.ChannelId.values(), //
				DeviceInformation.ChannelId.values(), //
				MoxaRegister.ChannelId.values());
	}

    @Activate
    @Modified
    protected void activate(final ComponentContext context, final io.openems.edge.moxa.model.e1211.Config config) throws OpenemsException {
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
						m(MoxaRegister.ChannelId.DO_04, new CoilElement(4)),
						m(MoxaRegister.ChannelId.DO_05, new CoilElement(5)),
						m(MoxaRegister.ChannelId.DO_06, new CoilElement(6)),
						m(MoxaRegister.ChannelId.DO_07, new CoilElement(7)),
						m(MoxaRegister.ChannelId.DO_08, new CoilElement(8)),
						m(MoxaRegister.ChannelId.DO_09, new CoilElement(9)),
						m(MoxaRegister.ChannelId.DO_10, new CoilElement(10)),
						m(MoxaRegister.ChannelId.DO_11, new CoilElement(11)),
						m(MoxaRegister.ChannelId.DO_12, new CoilElement(12)),
						m(MoxaRegister.ChannelId.DO_13, new CoilElement(13)),
						m(MoxaRegister.ChannelId.DO_14, new CoilElement(14)),
						m(MoxaRegister.ChannelId.DO_15, new CoilElement(15)),
						m(MoxaRegister.ChannelId.DO_16, new CoilElement(16))),
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
