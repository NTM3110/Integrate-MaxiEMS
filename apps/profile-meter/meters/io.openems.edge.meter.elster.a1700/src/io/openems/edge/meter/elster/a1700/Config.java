package io.openems.edge.meter.elster.a1700;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(name = "Meter Elster A1700", description = "Elster A1700 over IEC 62056-21 Mode C.")
@interface Config {
	@AttributeDefinition(name = "Component-ID")
	String id() default "a1700-meter0";

	@AttributeDefinition(name = "Alias")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Meter type")
	MeterType type() default MeterType.GRID;

	@AttributeDefinition(name = "IEC 62056-21 Bridge-ID")
	String bridge_id() default "iec6205621-serial0";

	@AttributeDefinition(name = "IEC 62056-21 Bridge target filter", description = "Auto-generated from Bridge-ID")
	String bridge_target() default "(enabled=true)";

	@AttributeDefinition(name = "Outstation", description = "Three-character IEC sign-on address, e.g. 005")
	String outstation() default "005";

	@AttributeDefinition(name = "Expected serial number", description = "Reject authentication if register 798001 identifies another meter")
	String expectedSerialNumber() default "21000568";

	@AttributeDefinition(name = "Active password level")
	PasswordLevel activePasswordLevel() default PasswordLevel.LEVEL_3;

	@AttributeDefinition(name = "Password 1", type = AttributeType.PASSWORD)
	String password1() default "0001ABCD";

	@AttributeDefinition(name = "Password 2", type = AttributeType.PASSWORD)
	String password2() default "ABCD0002";

	@AttributeDefinition(name = "Password 3", type = AttributeType.PASSWORD)
	String password3() default "FEDC0003";

	@AttributeDefinition(name = "Response timeout [ms]")
	int timeoutMs() default 8000;

	@AttributeDefinition(name = "Exchange delay [ms]")
	int exchangeDelayMs() default 300;

	@AttributeDefinition(name = "Instantaneous poll timeout [ms]")
	int instantaneousPollTimeoutMs() default 15000;

	@AttributeDefinition(name = "Sign-on attempts")
	int signOnAttempts() default 3;

	@AttributeDefinition(name = "Profile selector", description = "Used for automatic or undated reads; dated API reads calculate a recent-day selector")
	String profileSelector() default "0100";

	@AttributeDefinition(name = "Maximum profile blocks")
	int maxProfileBlocks() default 4095;

	@AttributeDefinition(name = "Current and instantaneous polling interval [s]", description = "Zero disables automatic polling")
	int pollingIntervalSeconds() default 60;

	@AttributeDefinition(name = "Automatic load profile reading")
	boolean autoReadProfile() default true;

	@AttributeDefinition(name = "Automatic historical reading")
	boolean autoReadHistorical() default true;

	@AttributeDefinition(name = "Wire log mode", description = "RAW exposes challenge/response material and is diagnostic only")
	WireLogMode wireLogMode() default WireLogMode.REDACTED;

	String webconsole_configurationFactory_nameHint() default "Meter Elster A1700 [{id}]";
}
