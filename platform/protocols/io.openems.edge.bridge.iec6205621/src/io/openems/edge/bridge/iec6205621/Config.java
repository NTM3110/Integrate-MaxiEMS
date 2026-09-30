package io.openems.edge.bridge.iec6205621;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Bridge IEC 62056-21 Serial", description = "Exclusive serial bridge for IEC 62056-21 meters.")
@interface Config {
	@AttributeDefinition(name = "Component-ID")
	String id() default "iec6205621-serial0";

	@AttributeDefinition(name = "Alias")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Serial port", description = "Example: /dev/ttyUSB0")
	String portName() default "/dev/ttyUSB0";

	@AttributeDefinition(name = "Baud rate")
	int baudRate() default 9600;

	@AttributeDefinition(name = "Data bits")
	int dataBits() default 7;

	@AttributeDefinition(name = "Parity")
	SerialParity parity() default SerialParity.EVEN;

	@AttributeDefinition(name = "Stop bits")
	SerialStopBits stopBits() default SerialStopBits.ONE;

	@AttributeDefinition(name = "Read timeout [ms]")
	int readTimeoutMs() default 8000;

	@AttributeDefinition(name = "Write timeout [ms]")
	int writeTimeoutMs() default 8000;

	String webconsole_configurationFactory_nameHint() default "Bridge IEC 62056-21 [{id}]";
}
