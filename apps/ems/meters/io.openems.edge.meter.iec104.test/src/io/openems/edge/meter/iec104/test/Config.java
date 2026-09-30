package io.openems.edge.meter.iec104.test;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Meter IEC104 Test", //
		description = "A test/demo meter for IEC104 bridge demonstrating various measurement types.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "iec104-meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID if empty")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Common Address", description = "The IEC104 Common Address (station address) of this meter")
	int commonAddress() default 1;

	@AttributeDefinition(name = "IEC104 Bridge-ID", description = "ID of the IEC104 Bridge to connect to")
	String bridge_id() default "iec1040";

	String webconsole_configurationFactory_nameHint() default "Meter IEC104 Test [{id}]";
}
