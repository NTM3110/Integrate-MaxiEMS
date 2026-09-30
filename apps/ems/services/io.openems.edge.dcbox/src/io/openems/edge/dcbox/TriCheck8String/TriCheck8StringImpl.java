package io.openems.edge.dcbox.TriCheck8String;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.WordOrder;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.RtsPvInverter;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "TriCheck.8String", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class TriCheck8StringImpl extends AbstractOpenemsModbusComponent implements TriCheck8String,
        ElectricityMeter,RtsPvInverter, ModbusComponent, OpenemsComponent {

    @Reference
    private ConfigurationAdmin cm;

    @Reference
    private ComponentManager cma;


    @Override
    @Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    private MeterType metertype = MeterType.PRODUCTION;
    private Config config = null;

    public TriCheck8StringImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                ModbusComponent.ChannelId.values(), //
                ElectricityMeter.ChannelId.values(), //
                TriCheck8String.ChannelId.values(), //
                RtsPvInverter.ChannelId.values()
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) throws OpenemsException {
        this.config = config;
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
        return new ModbusProtocol(this, //
                // Registers 1..23 form one contiguous FC3 block.
                new FC3ReadRegistersTask(30002, Priority.HIGH, //
                        m(RtsPvInverter.ChannelId.DC1_CURRENT,
                                new UnsignedWordElement(30002)),
                        m(RtsPvInverter.ChannelId.DC2_CURRENT,
                                new UnsignedWordElement(30003)),
                        m(RtsPvInverter.ChannelId.DC3_CURRENT,
                                new UnsignedWordElement(30004)),
                        m(RtsPvInverter.ChannelId.DC4_CURRENT,
                                new UnsignedWordElement(30005)),
                        m(RtsPvInverter.ChannelId.DC5_CURRENT,
                                new UnsignedWordElement(30006)),
                        m(RtsPvInverter.ChannelId.DC6_CURRENT,
                                new UnsignedWordElement(30007)),
                        m(RtsPvInverter.ChannelId.DC7_CURRENT,
                                new UnsignedWordElement(30008)),
                        m(RtsPvInverter.ChannelId.DC8_CURRENT,
                                new UnsignedWordElement(30009))),
                new FC3ReadRegistersTask(30040,Priority.HIGH,
                        m(RtsPvInverter.ChannelId.DC_VOLTAGE,
                                new UnsignedWordElement(30040), SCALE_FACTOR_3),
                        new DummyRegisterElement(30041,30046),
                        m(RtsPvInverter.ChannelId.DC_CURRENT,
                                new UnsignedWordElement(30047), SCALE_FACTOR_3),
                        m(RtsPvInverter.ChannelId.DC_POWER,
                                new UnsignedDoublewordElement(30048).wordOrder(WordOrder.MSWLSW)))




//                       m(RtsPvInverter.ChannelId.DC1_CURRENT,
//                               new SignedWordElement(1), ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC1_VOLTAGE,
//                               new SignedWordElement(2),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC2_CURRENT,
//                               new SignedWordElement(3),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC2_VOLTAGE,
//                               new SignedWordElement(4),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC3_CURRENT,
//                               new SignedWordElement(5),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC3_VOLTAGE,
//                               new SignedWordElement(6),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC4_CURRENT,
//                               new SignedWordElement(7),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC4_VOLTAGE,
//                               new SignedWordElement(8),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC5_CURRENT,
//                               new SignedWordElement(9),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC5_VOLTAGE,
//                               new SignedWordElement(10),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC6_CURRENT,
//                               new SignedWordElement(11),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC6_VOLTAGE,
//                               new SignedWordElement(12),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC7_CURRENT,
//                               new SignedWordElement(13),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC7_VOLTAGE,
//                               new SignedWordElement(14),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC8_CURRENT,
//                               new SignedWordElement(15),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC8_VOLTAGE,
//                               new SignedWordElement(16),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC9_CURRENT,
//                               new SignedWordElement(17),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC9_VOLTAGE,
//                               new SignedWordElement(18),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC_VOLTAGE,
//                               new SignedWordElement(19),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC_CURRENT,
//                               new SignedWordElement(20),ElementToChannelConverter.SCALE_FACTOR_1),
//                       m(RtsPvInverter.ChannelId.DC_POWER,
//                                new SignedWordElement(21),ElementToChannelConverter.SCALE_FACTOR_1),
//
//                       // Register 22 - alarm bits 0..15. Bit value 1 means alarm active.
//                       m(new BitsWordElement(22, this)
//                               // Alarm - electrical
//                               .bit(0, DcBox.ChannelId.OVER_VOLTAGE_ALARM)
//                               .bit(1, DcBox.ChannelId.UNDER_VOLTAGE_ALARM)
//                               .bit(2, DcBox.ChannelId.OVER_CURRENT_ALARM)
//                               .bit(3, DcBox.ChannelId.REVERSE_CURRENT_ALARM)
//                               // Alarm - string
//                               .bit(4, DcBox.ChannelId.STRING_CURRENT_ALARM)
//                               .bit(5, DcBox.ChannelId.STRING_LOSS_ALARM)
//                               .bit(6, DcBox.ChannelId.STRING_REVERSE_ALARM)
//                               .bit(7, DcBox.ChannelId.FUSE_ALARM)
//                               // Protection
//                               .bit(8, DcBox.ChannelId.SPD_ALARM)
//                               .bit(9, DcBox.ChannelId.BREAKER_TRIP_ALARM)
//                               .bit(10, DcBox.ChannelId.INSULATION_ALARM)
//                               .bit(11, DcBox.ChannelId.GROUND_FAULT_ALARM)
//                               // Environment
//                               .bit(12, DcBox.ChannelId.OVER_TEMPERATURE_ALARM)
//                               .bit(13, DcBox.ChannelId.SMOKE_ALARM)
//                               .bit(14, DcBox.ChannelId.WATER_ALARM)
//                               .bit(15, DcBox.ChannelId.DOOR_OPEN_ALARM)),
//
//                       // Register 23 - device alarms; bits 3..15 are reserved.
//                       m(new BitsWordElement(23, this)
//                               .bit(0, DcBox.ChannelId.COMMUNICATION_ALARM)
//                               .bit(1, DcBox.ChannelId.SENSOR_ALARM)
//                               .bit(2, DcBox.ChannelId.DEVICE_FAULT)))

                );
    }

    @Override
    public String debugLog() {
        return "L:" + this.getActivePower().asString();
    }

    @Override
    public MeterType getMeterType() {
        return MeterType.PRODUCTION;
    }
}
