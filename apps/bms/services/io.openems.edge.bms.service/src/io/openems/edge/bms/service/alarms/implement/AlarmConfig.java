package io.openems.edge.bms.service.alarms.implement;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "MaxiEMS BMS Monitoring Alarm Processor",
		description = "Processes atomic monitoring frames outside the OpenEMS scheduler cycle")
@interface AlarmConfig {

	@AttributeDefinition(name = "Enabled")
	boolean enabled() default true;

	@AttributeDefinition(name = "Provider poll interval (seconds)", min = "1")
	int pollSeconds() default 1;
}
