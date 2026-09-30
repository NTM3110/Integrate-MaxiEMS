package io.openems.edge.bms.service.history.implement;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "MaxiEMS BMS Monitoring History Writer",
		description = "Persists atomic BMS monitoring frames outside the OpenEMS scheduler cycle")
@interface Config {

	@AttributeDefinition(name = "Enabled")
	boolean enabled() default true;

	@AttributeDefinition(name = "Raw cadence (seconds)",
			description = "Minimum source captured-time distance between persisted raw frames; minimum 15 seconds",
			min = "15")
	int rawCadenceSeconds() default 30;

	@AttributeDefinition(name = "Raw retention (days)", min = "1")
	int rawRetentionDays() default 30;

	@AttributeDefinition(name = "5-minute retention (days)", min = "1")
	int fiveMinuteRetentionDays() default 365;

	@AttributeDefinition(name = "Hourly retention (days)", min = "1")
	int hourlyRetentionDays() default 1_825;
}
