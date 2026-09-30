package io.openems.edge.smartlogger.huawei3000a;

import io.openems.common.channel.Unit;

public enum InverterRegister {
	ACTIVE_POWER("ActivePower", 0, DataType.I32, 0, Unit.WATT), //
	REACTIVE_POWER("ReactivePower", 2, DataType.I32, 0, Unit.VOLT_AMPERE_REACTIVE), //
	DC_CURRENT("TotalDCCurrent", 4, DataType.I16, -2, Unit.AMPERE), //
	DC_TOTAL("TotalDCPower", 5, DataType.U32, 0, Unit.WATT), //
	INSULATION_RESISTANCE("InsulationResistance", 7, DataType.U16, -3, Unit.KILOOHM), //
	POWER_FACTOR("PowerFactor", 8, DataType.I16, -3, Unit.NONE), //
	MODBUS_COMMUNICATION_FAILED("InverterStatus", 9, DataType.U16, 0, Unit.NONE), //
	NONE("None", 10, DataType.U16, 0, Unit.NONE), //
	TEMPERATURE("Temperature", 11, DataType.I16, -1, Unit.DEGREE_CELSIUS), //
	MAJOR_FAULT_CODE("MajorFaultCode", 12, DataType.U32, 0, Unit.NONE), //
	MINOR_FAULT_CODE("MinorFaultCode", 14, DataType.U32, 0, Unit.NONE), //
	WARNING_CODE("WarningCode", 16, DataType.U32, 0, Unit.NONE), //


	// Add more registers here as needed (up to 24)
	;

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
