package io.openems.edge.data.smartlogger;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Data Energy", //
		description = "")
public @interface Config {
	@AttributeDefinition(name = "Component-ID")
	String id() default  "_data";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Initial Energy Yesterday", description = "Initial value for Energy Yesterday if not yet known")
	float initialEnergyYesterday() default 0f;

	String webconsole_configurationFactory_nameHint() default "Data Energy [{id}]";
}