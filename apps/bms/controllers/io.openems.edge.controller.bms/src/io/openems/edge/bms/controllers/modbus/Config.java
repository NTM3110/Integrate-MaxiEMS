package io.openems.edge.bms.controllers.modbus;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;

@ObjectClassDefinition(name = "BMS Modbus Server", description = "Exports the compact atomic BMS monitoring register contract over TCP or Serial RTU.")
@interface Config {

	@AttributeDefinition(name = "Component-ID")
	String id() default "ctrlBmsModbusTcp0";

	@AttributeDefinition(name = "Alias")
	String alias() default "BMS Modbus Server";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "TCP port", description = "The explicit TCP port on which to listen.")
	int port() default 1502;

	@AttributeDefinition(name = "TCP bind address", description = "IPv4 or IPv6 literal; hostnames are not accepted.")
	String bindAddress() default "0.0.0.0";

	@AttributeDefinition(name = "Unit-ID")
	int unitId() default 1;

	@AttributeDefinition(name = "Transport")
	Transport transport() default Transport.TCP;

	@AttributeDefinition(name = "Managed serial bridge ID")
	String serialPortId() default "";

	@AttributeDefinition(name = "Serial device path")
	String portName() default "";

	@AttributeDefinition(name = "Serial baud rate")
	int baudRate() default 9600;

	@AttributeDefinition(name = "Serial data bits")
	int databits() default 8;

	@AttributeDefinition(name = "Serial parity")
	String parity() default "NONE";

	@AttributeDefinition(name = "Serial stop bits")
	String stopbits() default "ONE";

	String webconsole_configurationFactory_nameHint() default "BMS Modbus Server [{id}]";
}
