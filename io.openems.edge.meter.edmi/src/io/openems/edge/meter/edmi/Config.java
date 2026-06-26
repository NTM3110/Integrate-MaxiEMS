package io.openems.edge.meter.edmi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

enum EnergyMeterRole {
	SOURCE,
	SELF_USE,
	MAIN,
	BACKUP
}

enum EnergySourceType {
	NONE,
	BESS,
	RTS
}

@ObjectClassDefinition(//
		name = "Meter EDMI", //
		description = "An EDMI meter.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "edmi-meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID if empty")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name= "Meter's serial number", description = "Serial number of this meter")
	int serial_number() default 251308613;

	@AttributeDefinition(name = "Meter's username", description = "Login username of this meter")
	String username() default "EDMI";

	@AttributeDefinition(name = "Meter's password", description = "Login password of this meter")
	String password() default "IMDEIMDE";

	@AttributeDefinition(name = "EDMI Bridge-ID", description = "ID of the EDMI Bridge")
	String bridge_id() default "edmi0";

	@AttributeDefinition(name = "Energy meter role", description = "SOURCE, SELF_USE, MAIN or BACKUP for energy separation")
	EnergyMeterRole energyRole() default EnergyMeterRole.SOURCE;

	@AttributeDefinition(name = "Energy source type", description = "BESS or RTS for SOURCE meters; NONE for other roles")
	EnergySourceType energySourceType() default EnergySourceType.NONE;

	// PostgreSQL sync settings
	@AttributeDefinition(name = "Enable PostgreSQL Sync", description = "Sync meter configuration to PostgreSQL")
	boolean enablePostgreSqlSync() default false;

	@AttributeDefinition(name = "PostgreSQL Host", description = "PostgreSQL host for meter sync")
	String postgreSqlHost() default "localhost";

	@AttributeDefinition(name = "PostgreSQL Port", description = "PostgreSQL port for meter sync")
	int postgreSqlPort() default 5432;

	@AttributeDefinition(name = "PostgreSQL Database", description = "PostgreSQL database for meter sync")
	String postgreSqlDatabase() default "energy_reports";

	@AttributeDefinition(name = "PostgreSQL User", description = "PostgreSQL username for meter sync")
	String postgreSqlUser() default "openems";

	@AttributeDefinition(name = "PostgreSQL Password", description = "PostgreSQL password for meter sync")
	String postgreSqlPassword() default "";

	String webconsole_configurationFactory_nameHint() default "Meter EDMI [{id}]";
}
