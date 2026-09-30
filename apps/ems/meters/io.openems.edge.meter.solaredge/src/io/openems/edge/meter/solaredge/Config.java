package io.openems.edge.meter.solaredge;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Meter SolarEdge Modbus", //
		description = "")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default 1;
	
	@AttributeDefinition(name = "Base Address", description = "The Base Address of the Meter registers.")
	int baseAddress() default 40000;
	
	@AttributeDefinition(name = "Invert Power", description = "Inverts all Power values.")
	boolean invert() default false;

	String webconsole_configurationFactory_nameHint() default "Meter SolarEdge [{id}]";

}