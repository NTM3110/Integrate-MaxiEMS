package io.openems.edge.bridge.dlms;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Bridge DLMS Serial", description = "Provides a service for connecting to a DLMS device via Serial (RS485/RS232).")
@interface ConfigSerial {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "bridgeDlmsSerial0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Port-Name", description = "The name of the serial port (e.g. /dev/ttyUSB0 or COM1)")
	String portName() default "/dev/ttyUSB0";

	@AttributeDefinition(name = "Baudrate", description = "The baudrate (e.g. 9600)")
	int baudRate() default 9600;

	@AttributeDefinition(name = "Logical Address", description = "DLMS Logical Device Address")
	int logicalAddress() default 1;

	@AttributeDefinition(name = "Use Logical Name Referencing", description = "Standard is LN (true). Use false for SN referencing meters.")
	boolean useLogicalNameReferencing() default false;

	@AttributeDefinition(name = "Parity", description = "The parity (None, Even, Odd, Mark, Space)")
	String parity() default "None";

	@AttributeDefinition(name = "Stopbits", description = "The number of stopbits (1, 2)")
	int stopBits() default 1;

	@AttributeDefinition(name = "Databits", description = "The number of databits (8, 7)")
	int dataBits() default 8;

	@AttributeDefinition(name = "Authentication", description = "The authentication level (None, Low, High, HighMd5, HighSha1, HighGMac, HighSha256)")
	String authentication() default "None";

	@AttributeDefinition(name = "Password", description = "The password for authentication")
	String password() default "";

	@AttributeDefinition(name = "Security", description = "The security level (None, Authentication, Encryption, AuthenticationEncryption)")
	String security() default "None";

	@AttributeDefinition(name = "Output File", description = "Optional file to save the meter's object model (e.g. meter_cache.xml)")
	String outputFile() default "meter_cache.xml";

	@AttributeDefinition(name = "Cycle Delay", description = "Delay between two DLMS meter reading cycles in milliseconds")
	int delay() default 1000;

	String webconsole_configurationFactory_nameHint() default "Bridge DLMS Serial [{id}]";
}
