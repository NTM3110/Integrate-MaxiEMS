package io.openems.edge.moxa.model.e1210;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.CoilElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.task.FC2ReadInputsTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.device.api.DeviceInformation;
import io.openems.edge.moxa.api.MoxaRegister;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Moxa.E1210", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)

public class MoxaE1210Impl extends AbstractOpenemsModbusComponent //
        implements MoxaE1210, DeviceInformation, ModbusComponent, OpenemsComponent {

    @Reference
    private ConfigurationAdmin cm;

    @Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    public Config config;

	public MoxaE1210Impl() {
		super(OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				MoxaE1210.ChannelId.values(), //
				DeviceInformation.ChannelId.values(), //
				MoxaRegister.ChannelId.values());
	}

    @Activate
    @Modified
    protected void activate(final ComponentContext context, final Config config) throws OpenemsException {
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
				new FC2ReadInputsTask(10001, Priority.HIGH, //
						m(MoxaRegister.ChannelId.DI_01, new CoilElement(10001)), //
						m(MoxaRegister.ChannelId.DI_02, new CoilElement(10002)), //
						m(MoxaRegister.ChannelId.DI_03, new CoilElement(10003)), //
						m(MoxaRegister.ChannelId.DI_04, new CoilElement(10004)), //
						m(MoxaRegister.ChannelId.DI_05, new CoilElement(10005)), //
						m(MoxaRegister.ChannelId.DI_06, new CoilElement(10006)), //
						m(MoxaRegister.ChannelId.DI_07, new CoilElement(10007)), //
						m(MoxaRegister.ChannelId.DI_08, new CoilElement(10008)), //
						m(MoxaRegister.ChannelId.DI_09, new CoilElement(10009)), //
						m(MoxaRegister.ChannelId.DI_10, new CoilElement(10010)), //
						m(MoxaRegister.ChannelId.DI_11, new CoilElement(10011)), //
						m(MoxaRegister.ChannelId.DI_12, new CoilElement(10012)), //
						m(MoxaRegister.ChannelId.DI_13, new CoilElement(10013)), //
						m(MoxaRegister.ChannelId.DI_14, new CoilElement(10014)), //
						m(MoxaRegister.ChannelId.DI_15, new CoilElement(10015)), //
						m(MoxaRegister.ChannelId.DI_16, new CoilElement(10016))), //
				new FC4ReadInputRegistersTask(35001, Priority.LOW, //
						m(DeviceInformation.ChannelId.MODEL,
								new StringWordElement(35001, 10)), //
						new DummyRegisterElement(35011, 35029), //
						m(DeviceInformation.ChannelId.FIRMWARE_VERSION,
								new StringWordElement(35030, 2),
								new ElementToChannelConverter(MoxaE1210Impl::parseFirmwareVersion)), //
						new DummyRegisterElement(35032, 35040), //
						m(DeviceInformation.ChannelId.DEVICE_NAME,
								new StringWordElement(35041, 30))),
				new FC4ReadInputRegistersTask(35021, Priority.HIGH,
						m(DeviceInformation.ChannelId.DEVICE_UPTIME,
								new UnsignedDoublewordElement(35021)))
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
