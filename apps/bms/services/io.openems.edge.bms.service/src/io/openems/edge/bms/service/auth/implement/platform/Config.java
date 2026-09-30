package io.openems.edge.bms.service.auth.implement.platform;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "MaxiEMS Platform Authentication")
@interface Config {

	@AttributeDefinition(name = "Bootstrap administrator username")
	String bootstrapAdminUsername() default "admin";

	@AttributeDefinition(name = "Bootstrap administrator name")
	String bootstrapAdminName() default "Administrator";

	@AttributeDefinition(name = "Bootstrap administrator password", type = AttributeType.PASSWORD,
			description = "Used once, only when no platform account exists. Set this before first start and clear it afterwards.")
	String bootstrapAdminPassword() default "admin";

	@AttributeDefinition(name = "Session lifetime in hours")
	int sessionLifetimeHours() default 8;

	@AttributeDefinition(name = "Minimum password length")
	int minimumPasswordLength() default 12;

	@AttributeDefinition(name = "Import legacy OpenMUC administrator",
			description = "One-time migration. When no MaxiEMS platform user exists, imports account 1 from the configured OpenMUC endpoint and stores only its password hash.")
	boolean importLegacyOpenmucAdministrator() default false;

	@AttributeDefinition(name = "Legacy OpenMUC account endpoint",
			description = "Full URL of OpenMUC's legacy account endpoint, e.g. http://localhost:8888/rest/latest-value/account?accountID=1")
	String legacyOpenmucAccountUrl() default "";
}
