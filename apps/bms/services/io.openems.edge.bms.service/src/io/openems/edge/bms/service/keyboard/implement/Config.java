package io.openems.edge.bms.service.keyboard.implement;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "BMS Kiosk Keyboard")
@interface Config {
	@AttributeDefinition(name = "Keyboard helper path")
	String helperPath() default "/usr/local/sbin/maxiems-keyboard";
}
