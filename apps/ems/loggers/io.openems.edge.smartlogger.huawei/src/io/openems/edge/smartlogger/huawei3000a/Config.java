package io.openems.edge.smartlogger.huawei3000a;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Smart Logger Huawei 3000A", //
		description = "")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "SmartLogger0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default 1;

	@AttributeDefinition(name = "Inverter Addresses", description = "Comma-separated list of inverter addresses (e.g. 1, 2, 3)")
	String inverterAddresses() default "1";

	@AttributeDefinition(name = "Max Active Power [W]", description = "Total maximum active power of all connected inverters")
	int maxActivePowerWatts() default 100000;

	@AttributeDefinition(name = "Is Meter enabled?", description = "Enable reading from Meter")
	boolean meterEnabled() default true;

	@AttributeDefinition(name = "Meter Unit-ID", description = "The Unit-ID of the Meter (for auto-activation).")
	int meterUnitId() default 2;

	@AttributeDefinition(name = "Invert Meter Power", description = "Inverts all Power values of the auto-activated Meter")
	boolean invert_meter() default false;

	String webconsole_configurationFactory_nameHint() default "Smart Logger Huawei 3000A [{id}]";

}