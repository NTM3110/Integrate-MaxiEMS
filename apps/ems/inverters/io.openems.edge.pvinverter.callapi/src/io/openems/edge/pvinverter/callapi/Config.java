package io.openems.edge.pvinverter.callapi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "PvInverter CallApi", //
		description = "Implements the CallApi PvInverter.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "pvInverter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "API IP", description = "IP of API host (without http://)")
	String ip() default "192.168.127.254";

	@AttributeDefinition(name = "PvInverter ID", description = "The ID of the PV Inverter to find in the JSON response")
	String pvInverterId() default "pvarray-0";

	@AttributeDefinition(name = "Invert Power", description = "Inverts ALL Power values, inverts current values, swaps production and consumption energy, i.e. Power is multiplied with -1.")
	boolean invert() default false;

	String webconsole_configurationFactory_nameHint() default "PvInverter CallApi [{id}]";
}
