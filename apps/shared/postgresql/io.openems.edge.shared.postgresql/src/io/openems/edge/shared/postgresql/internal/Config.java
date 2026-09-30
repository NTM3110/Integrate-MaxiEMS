package io.openems.edge.shared.postgresql.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Shared PostgreSQL connection")
@interface Config {

	@AttributeDefinition(name = "Connection ID")
	String id();

	@AttributeDefinition(name = "PostgreSQL host")
	String host() default "localhost";

	@AttributeDefinition(name = "PostgreSQL port")
	int port() default 5432;

	@AttributeDefinition(name = "Database")
	String database();

	@AttributeDefinition(name = "User")
	String user();

	@AttributeDefinition(name = "Password", type = AttributeType.PASSWORD)
	String password() default "";

	@AttributeDefinition(name = "Connection pool size")
	int poolSize() default 5;
}
