package io.openems.edge.controller.maximeter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller MaxiMeter", //
		description = "Provides PostgreSQL database access for MaxiMeter API")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "maximeter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
	String alias() default "MaxiMeter Controller";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Database Host", description = "PostgreSQL database host")
	String dbHost() default "localhost";

	@AttributeDefinition(name = "Database Port", description = "PostgreSQL database port")
	int dbPort() default 5432;

	@AttributeDefinition(name = "Database Name", description = "PostgreSQL database name")
	String dbName() default "maximeter";

	@AttributeDefinition(name = "Database User", description = "PostgreSQL database user")
	String dbUser() default "maximeter_app";

	@AttributeDefinition(name = "Database Password", description = "PostgreSQL database password")
	String dbPassword() default "";

	String webconsole_configurationFactory_nameHint() default "Controller MaxiMeter [{id}]";
}