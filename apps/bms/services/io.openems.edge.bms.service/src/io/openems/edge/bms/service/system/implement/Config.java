package io.openems.edge.bms.service.system.implement;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "BMS System Commands", description = "Controlled privileged commands for the MaxiEMS appliance.")
@interface Config {

	@AttributeDefinition(name = "Systemctl path")
	String systemctlPath() default "/usr/bin/systemctl";
}
