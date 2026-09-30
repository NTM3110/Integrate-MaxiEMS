package io.openems.edge.controller.thingsboard;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller IoT AT-Energy", //
		description = "Pushes OpenEMS data to ThingsBoard via MQTT Gateway API. Automatically detects child devices.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlThingsboard0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
	String alias() default "";

	@AttributeDefinition(name = "Is Enabled?", description = "Enable or disable this Component")
	boolean enabled() default true;

	@AttributeDefinition(name = "ThingsBoard Host", description = "ThingsBoard MQTT server hostname")
	String host() default "222.252.22.158";

	@AttributeDefinition(name = "Port", description = "MQTT port")
	int port() default 8090;

	@AttributeDefinition(name = "Access Token", description = "ThingsBoard Gateway device access token")
	String accessToken() default "";

	@AttributeDefinition(name = "Cycle Time (s)", description = "Publish interval in seconds")
	int cycleTime() default 300;

	String webconsole_configurationFactory_nameHint() default "Controller IoT AT-Energy [{id}]";
}
