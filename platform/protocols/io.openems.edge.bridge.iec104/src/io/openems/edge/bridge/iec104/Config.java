package io.openems.edge.bridge.iec104;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Bridge IEC 104", description = "Provides a service for connecting to an IEC 60870-5-104 Slave/Server.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "iec1040";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "IP Address", description = "The IP address of the IEC 104 Slave.")
	String ip() default "127.0.0.1";

	@AttributeDefinition(name = "Port", description = "The port of the IEC 104 Slave.")
	int port() default 2404;

	@AttributeDefinition(name = "Connection Timeout", description = "Connection timeout in ms.")
	int connectionTimeout() default 20000;

	@AttributeDefinition(name = "Cyclic Interrogation Interval", description = "Interval in seconds for periodic General Interrogation. Set to 0 to disable.")
	int cyclicInterrogationInterval() default 60;

	String webconsole_configurationFactory_nameHint() default "Bridge IEC 104 [{id}]";

}
