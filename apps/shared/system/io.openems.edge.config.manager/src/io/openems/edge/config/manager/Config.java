package io.openems.edge.config.manager;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
		name = "Config Manager",
		description = "Imports and exports OpenEMS OSGi configurations.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "configManager0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
	String alias() default "Config Manager";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;
}
