package io.openems.edge.bms.controllers.monitoring;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Controller BMS Monitoring", description = "Builds one atomic monitoring frame from one calculated BMS string snapshot")
@interface Config {

	@AttributeDefinition(name = "Component-ID")
	String id() default "ctrlBmsMonitoring-str1";

	@AttributeDefinition(name = "Alias")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "String key")
	String stringKey() default "str1";

	@AttributeDefinition(name = "Calculator Component-ID")
	String calculatorId() default "ctrlBmsHuasuCalc-str1";

	@AttributeDefinition(name = "Discharge current direction",
			description = "POSITIVE when positive raw current means discharge; otherwise NEGATIVE")
	String dischargeCurrentDirection() default "NEGATIVE";

	@AttributeDefinition(name = "Snapshot freshness (seconds)")
	int snapshotFreshnessSeconds() default 60;

	@AttributeDefinition(name = "Battery low voltage (V)")
	double batteryVoltageLowV() default 200.0;

	@AttributeDefinition(name = "Battery high voltage (V)")
	double batteryVoltageHighV() default 242.0;

	@AttributeDefinition(name = "Low SoC (%)")
	double lowSocPct() default 70.0;

	@AttributeDefinition(name = "High ambient temperature (C)")
	double highAmbientTemperatureC() default 40.0;

	@AttributeDefinition(name = "Current deadband (A)")
	double currentDeadbandA() default 0.2;

	@AttributeDefinition(name = "Float voltage tolerance (V/string)")
	double floatVoltageToleranceV() default 1.0;

	@AttributeDefinition(name = "Operating-mode stable window (seconds)")
	int modeStableSeconds() default 60;
}
