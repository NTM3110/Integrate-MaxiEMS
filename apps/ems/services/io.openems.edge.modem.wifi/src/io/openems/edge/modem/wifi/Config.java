package io.openems.edge.modem.wifi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "io.openems.edge.modem.wifi", //
		description = "")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "io.openems.edge.modem.wifi0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "NameWifi", description =  "SSID Wifi Connect?")
	String nameWiFi() default "";

	@AttributeDefinition(name = "PassWifi", description = "Password Wifi Connect?")
	String passWiFi() default "";

	String webconsole_configurationFactory_nameHint() default "Modem Wifi [{id}]";
}