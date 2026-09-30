package io.openems.edge.bms.controllers.modbus;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Controller BMS Modbus/TCP Server", description = "Exports legacy calculator snapshots and atomic monitoring frames as read-only Modbus input registers.")
@interface Config {

	@AttributeDefinition(name = "Component-ID")
	String id() default "ctrlBmsModbusTcp0";

	@AttributeDefinition(name = "Alias")
	String alias() default "BMS Modbus/TCP Server";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "TCP port", description = "The explicit TCP port on which to listen.")
	int port() default 1502;

	@AttributeDefinition(name = "Unit-ID")
	int unitId() default 1;

	String webconsole_configurationFactory_nameHint() default "BMS Modbus/TCP Server [{id}]";
}
