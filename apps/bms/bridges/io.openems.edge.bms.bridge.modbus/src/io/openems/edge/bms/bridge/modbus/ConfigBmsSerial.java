package io.openems.edge.bms.bridge.modbus;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.bridge.modbus.api.LogVerbosity;
import io.openems.edge.bridge.modbus.api.Parity;
import io.openems.edge.bridge.modbus.api.Stopbit;

@ObjectClassDefinition(name = "BMS Modbus/RTU Serial Bridge", description = "Reads registered BMS TA/TC protocols in complete acquisition batches.")
@interface ConfigBmsSerial {
	@AttributeDefinition(name = "Component-ID")
	String id() default "modbus0";

	@AttributeDefinition(name = "Alias")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Port-Name")
	String portName() default "/dev/ttyUSB0";

	@AttributeDefinition(name = "Baudrate")
	int baudRate() default 9600;

	@AttributeDefinition(name = "Databits")
	int databits() default 8;

	@AttributeDefinition(name = "Stopbits")
	Stopbit stopbits() default Stopbit.ONE;

	@AttributeDefinition(name = "Parity")
	Parity parity() default Parity.NONE;

	@AttributeDefinition(name = "Log-Verbosity")
	LogVerbosity logVerbosity() default LogVerbosity.NONE;

	@AttributeDefinition(name = "Invalidate elements after how many read errors?")
	int invalidateElementsAfterReadErrors() default 1;

	@AttributeDefinition(name = "Response timeout (ms)", description = "Response timeout per TA/TC request; range 10..10000 ms")
	int responseTimeoutMs() default 100;

	String webconsole_configurationFactory_nameHint() default "BMS Modbus/RTU Serial [{id}]";
}
