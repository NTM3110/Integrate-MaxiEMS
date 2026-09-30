package io.openems.edge.controller.pvinverter.powerlimit;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller PV-Inverter Power Limit", //
		description = "Defines a fixed power limitation to PV inverter.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlPvInverterPowerLimit0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "PV-Inverter-ID", description = "ID of PV-Inverter device.")
	String pvInverter_id();

	@AttributeDefinition(name = "Use Percentage", description = "If true, use percentage limit instead of absolute Watt value")
	boolean usePercentage() default false;

	@AttributeDefinition(name = "Power Limit [W]", description = "Fixed power limit in Watts (used when usePercentage is false)")
	int powerLimit() default 0;

	@AttributeDefinition(name = "Power Limit [%]", description = "Fixed power limit as percentage 0-100 (used when usePercentage is true)")
	int powerLimitPercent() default 100;

	String webconsole_configurationFactory_nameHint() default "Controller PV-Inverter Power Limit [{id}]";
}