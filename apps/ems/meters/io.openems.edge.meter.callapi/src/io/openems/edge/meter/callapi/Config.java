package io.openems.edge.meter.callapi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(name = "Meter OneController", //
		description = "Implements the CallApi meter.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Meter-Type", description = "Grid (default), Production, Consumption")
	MeterType type() default MeterType.GRID;

	@AttributeDefinition(name = "API IP", description = "IP of API host (without http://)")
	String ip() default "192.168.127.254";

	@AttributeDefinition(name = "Meter ID", description = "The ID of the meter to find in the JSON response")
	String meterId() default "grid_meter";

	@AttributeDefinition(name = "Invert Power", description = "Inverts ALL Power values, inverts current values, swaps production and consumption energy, i.e. Power is multiplied with -1.")
	boolean invert() default false;

	String webconsole_configurationFactory_nameHint() default "Meter CallApi [{id}]";
}
