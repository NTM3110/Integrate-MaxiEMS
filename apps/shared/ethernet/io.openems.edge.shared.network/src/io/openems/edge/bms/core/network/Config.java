package io.openems.edge.bms.core.network;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "BMS Network Service", description = "Maps BMS WAN labels to NetworkManager interfaces.")
@interface Config {

	@AttributeDefinition(name = "Component-ID")
	String id() default "bmsNetwork0";

	@AttributeDefinition(name = "Alias")
	String alias() default "BMS Network";

	@AttributeDefinition(name = "WAN 0 interface", description = "Linux interface exposed as logical WAN 0.")
	String wan0Interface() default "eth1";

	@AttributeDefinition(name = "WAN 0 label")
	String wan0Label() default "WAN 0";

	@AttributeDefinition(name = "WAN 1 interface", description = "Linux interface exposed as logical WAN 1.")
	String wan1Interface() default "eth0";

	@AttributeDefinition(name = "WAN 1 label")
	String wan1Label() default "WAN 1";

	@AttributeDefinition(name = "Network configuration script", description = "Absolute path, or a path relative to the Edge working directory.")
	String netcfgScriptPath() default "/usr/local/sbin/maxiems-netcfg";
}
