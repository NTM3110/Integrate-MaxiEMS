package io.openems.edge.bms.service.persistence.implement;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "MaxiEMS BMS PostgreSQL")
@interface Config {

	@AttributeDefinition(name = "PostgreSQL host")
	String pgHost() default "localhost";

	@AttributeDefinition(name = "PostgreSQL port")
	int pgPort() default 5432;

	@AttributeDefinition(name = "Database")
	String database() default "maxiems";

	@AttributeDefinition(name = "User")
	String pgUser() default "postgres";

	@AttributeDefinition(name = "Password", type = AttributeType.PASSWORD)
	String pgPassword() default "admin";

	@AttributeDefinition(name = "Connection pool size")
	int connectionPoolSize() default 5;
}
