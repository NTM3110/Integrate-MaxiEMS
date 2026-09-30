package io.openems.edge.meter.viethao;

import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface MeterViethao extends ElectricityMeter, ModbusComponent, OpenemsComponent {
}
