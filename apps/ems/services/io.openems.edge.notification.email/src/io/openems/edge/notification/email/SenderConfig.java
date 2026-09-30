package io.openems.edge.notification.email;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Edge Email Sender", description = "Provides an SMTP account for sending email")
@interface SenderConfig {
	@AttributeDefinition(name = "Component-ID")
	String id() default "emailSender0";

	@AttributeDefinition(name = "Alias")
	String alias() default "";

	@AttributeDefinition(name = "Enabled")
	boolean enabled() default true;

	@AttributeDefinition(name = "SMTP Host")
	String smtpHost();

	@AttributeDefinition(name = "SMTP Port")
	int smtpPort() default 587;

	@AttributeDefinition(name = "Security")
	SmtpSecurity security() default SmtpSecurity.STARTTLS;

	@AttributeDefinition(name = "Username", description = "Leave empty for an unauthenticated SMTP relay")
	String username() default "";

	@AttributeDefinition(name = "Password", type = AttributeType.PASSWORD)
	String password() default "";

	@AttributeDefinition(name = "From Address")
	String fromAddress();

	@AttributeDefinition(name = "From Name")
	String fromName() default "MAXiEMS Alert";

	@AttributeDefinition(name = "Connection Timeout [ms]")
	int connectionTimeoutMs() default 10_000;

	@AttributeDefinition(name = "Read/Write Timeout [ms]")
	int sendTimeoutMs() default 30_000;

	String webconsole_configurationFactory_nameHint() default "Edge Email Sender [{id}]";
}
