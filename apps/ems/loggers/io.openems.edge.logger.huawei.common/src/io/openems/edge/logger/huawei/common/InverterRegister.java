package io.openems.edge.logger.huawei.common;

import io.openems.common.channel.Unit;

public enum InverterRegister {
	ACTIVE_POWER("ACTIVE_POWER", 0, DataType.I32, 0, Unit.WATT), //
	REACTIVE_POWER("REACTIVE_POWER", 2, DataType.I32, 0, Unit.VOLT_AMPERE_REACTIVE), //
	DC_CURRENT("DC_CURRENT", 4, DataType.I16, -2, Unit.AMPERE), //
	DC_TOTAL("DC_TOTAL", 5, DataType.U32, 0, Unit.WATT), //
	INSULATION_RESISTANCE("INSULATION_RESISTANCE", 7, DataType.U16, -3, Unit.KILOOHM), //
	POWER_FACTOR("POWER_FACTOR", 8, DataType.I16, -3, Unit.NONE), //
	MODBUS_COMMUNICATION_FAILED("MODBUS_COMMUNICATION_FAILED", 9, DataType.U16, 0, Unit.NONE), //
	NONE("NONE", 10, DataType.U16, 0, Unit.NONE), //
	TEMPERATURE("TEMPERATURE", 11, DataType.I16, -1, Unit.DEGREE_CELSIUS), //
	MAJOR_FAULT_CODE("MAJOR_FAULT_CODE", 12, DataType.U32, 0, Unit.NONE), //
	MINOR_FAULT_CODE("MINOR_FAULT_CODE", 14, DataType.U32, 0, Unit.NONE), //
	WARNING_CODE("WARNING_CODE", 16, DataType.U32, 0, Unit.NONE); //

	public final String name;
	public final int offset;
	public final DataType dataType;
	public final int scaleExponent;
	public final Unit unit;

	private InverterRegister(String name, int offset, DataType dataType, int scaleExponent, Unit unit) {
		this.name = name;
		this.offset = offset;
		this.dataType = dataType;
		this.scaleExponent = scaleExponent;
		this.unit = unit;
	}

	public enum DataType {
		U16, U32, I16, I32;
	}
}
