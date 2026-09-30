package io.openems.edge.ess.deye;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "ESS Deye Hybrid", //
		description = "Managed ESS adapter for a Deye Hybrid inverter.")
@interface DeyeHybridEssConfig {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Deye inverter ID", description = "Component-ID of PV-Inverter.Deye.Hybrid")
	String inverter_id() default "pvInverter0";

	@AttributeDefinition(name = "Deye inverter target filter", description = "Auto-generated from Deye inverter ID")
	String inverter_target() default "(enabled=true)";

	@AttributeDefinition(name = "Battery capacity override", //
			description = "Usable battery capacity in Wh. Set to 0 if it is not available; balancing control does not require it.")
	int capacity() default 0;

	@AttributeDefinition(name = "Maximum charge power override", //
			description = "Optional AC charge limit in W. Set to 0 to use the live BMS voltage/current limit.")
	int maxChargePower() default 0;

	@AttributeDefinition(name = "Maximum discharge power override", //
			description = "Optional AC discharge limit in W. Set to 0 to use the live BMS voltage/current limit.")
	int maxDischargePower() default 0;

	@AttributeDefinition(name = "Minimum SOC", description = "Discharging is blocked at or below this SOC")
	int minSoc() default 10;

	@AttributeDefinition(name = "Maximum SOC", description = "Charging is blocked at or above this SOC")
	int maxSoc() default 100;

	@AttributeDefinition(name = "Remote-enable raw value", //
			description = "Vendor-confirmed value for register 1100. -1 keeps ESS control blocked.")
	int remoteEnableValue() default -1;

	@AttributeDefinition(name = "EMS-mode raw value", //
			description = "Vendor-confirmed value for register 141. -1 keeps ESS control blocked.")
	int emsModeValue() default -1;

	@AttributeDefinition(name = "Heartbeat raw value", //
			description = "Vendor-confirmed value for register 1101. -1 disables heartbeat writes.")
	int heartbeatValue() default -1;

	@AttributeDefinition(name = "Enable grid charging", //
			description = "Value written to Deye register 130 while applying EMS setpoints")
	boolean gridChargeEnabled() default false;

	@AttributeDefinition(name = "Invert setpoint sign", //
			description = "Invert OpenEMS charge-negative/discharge-positive sign before writing register 1109")
	boolean invertSetpointSign() default false;

	@AttributeDefinition(name = "Invert telemetry sign", //
			description = "Invert register 590 sign before exposing SymmetricEss ActivePower")
	boolean invertTelemetrySign() default false;

	String webconsole_configurationFactory_nameHint() default "ESS Deye Hybrid [{id}]";
}
