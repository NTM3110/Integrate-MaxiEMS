package io.openems.edge.bridge.edmi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.shared.influxdb.QueryLanguageConfig;

@ObjectClassDefinition(//
		name = "Bridge EDMI", //
		description = "Provides a service for connecting to EDMI devices via Serial/TCP.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "edmi0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID if empty")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Port-Name", description = "The name of the serial port - e.g. '/dev/ttyUSB0' or 'COM3'")
	String portName() default "/dev/ttyUSB0";

	@AttributeDefinition(name = "Baudrate", description = "The baudrate - e.g. 9600, 19200, 38400, 57600 or 115200")
	int baudRate() default 9600;

	@AttributeDefinition(name = "Databits", description = "The number of databits - e.g. 8")
	int databits() default 8;

	@AttributeDefinition(name = "Stopbits", description = "The number of stopbits - '1', '1.5' or '2'")
	Stopbit stopbits() default Stopbit.ONE;

	@AttributeDefinition(name = "Parity", description = "The parity - 'none', 'even', 'odd', 'mark' or 'space'")
	Parity parity() default Parity.NONE;

		@AttributeDefinition(name = "Query language", description = "Query language Flux or InfluxQL")
	QueryLanguageConfig queryLanguage() default QueryLanguageConfig.INFLUX_QL;

	@AttributeDefinition(name = "URL", description = "The InfluxDB URL, e.g.: http://localhost:8086")
	String url() default "http://localhost:8086";

	@AttributeDefinition(name = "Org", description = "The Organisation; for InfluxDB v1: '-'")
	String org() default "-";

	@AttributeDefinition(name = "ApiKey", description = "The ApiKey; for InfluxDB v1: 'username:password', e.g. 'admin:admin'")
	String apiKey();

	@AttributeDefinition(name = "Bucket", description = "The bucket name; for InfluxDB v1: 'database/retentionPolicy', e.g. 'db/data'")
	String bucket();

	@AttributeDefinition(name = "Measurement", description = "The InfluxDB measurement")
	String measurement() default "data";

	@AttributeDefinition(name = "No of Cycles", description = "How many Cycles till data is written to InfluxDB.")
	int noOfCycles() default 1;

	@AttributeDefinition(name = "Number of max scheduled tasks", description = "Max-Size of Queued tasks.")
	int maxQueueSize() default 5000;

	@AttributeDefinition(name = "Read-Only mode", description = "Activates the read-only mode. Then no data is written to InfluxDB.")
	boolean isReadOnly() default false;

	@AttributeDefinition(name = "EDMI profile survey", description = "Survey id for EDMI profile reads. Accepts decimal or hex, e.g. 0x0325.")
	String profileSurvey() default "0x0325";

	@AttributeDefinition(name = "Profile interval minutes", description = "Expected EDMI profile interval length in minutes.")
	int profileIntervalMinutes() default 30;

	@AttributeDefinition(name = "Profile first/read delay minutes", description = "Delay after each interval boundary before reading the finalized profile record.")
	int profileFirstReadDelayMinutes() default 5;

	@AttributeDefinition(name = "Profile retry delay minutes", description = "Delay before retrying a failed EDMI task.")
	int profileRetryMinutes() default 5;

	@AttributeDefinition(name = "Profile finalize after minutes", description = "How long an interval must be closed before it is treated as finalized.")
	int profileFinalizeAfterMinutes() default 30;

	@AttributeDefinition(name = "Profile max records", description = "Maximum profile records to process per profile task execution.")
	int profileMaxRecords() default 5;

	@AttributeDefinition(name = "Profile timestamp aliases", description = "Pipe-separated profile channel names for timestamp matching.")
	String profileTimestampAliases() default "DateTime|time_stamp|timestamp";

	@AttributeDefinition(name = "Profile record-status aliases", description = "Pipe-separated profile channel names for record-status matching.")
	String profileRecordStatusAliases() default "Record Status|record_status|EFA Status";

	@AttributeDefinition(name = "Profile import Wh aliases", description = "Pipe-separated profile channel names for total import Wh matching.")
	String profileImportWhAliases() default "Total Energy Tot IMP Wh @|To IMP Wh|Tot IMP Wh";

	@AttributeDefinition(name = "Profile export Wh aliases", description = "Pipe-separated profile channel names for total export Wh matching.")
	String profileExportWhAliases() default "Total Energy Tot EXP Wh @|To EXP Wh|Tot EXP Wh";

	@AttributeDefinition(name = "Profile import VAh aliases", description = "Pipe-separated profile channel names for total import VAh matching.")
	String profileImportVahAliases() default "Total Energy Tot IMP va @|To IMP va|Tot IMP va";

	@AttributeDefinition(name = "Profile export VAh aliases", description = "Pipe-separated profile channel names for total export VAh matching.")
	String profileExportVahAliases() default "Total Energy Tot EXP va @|To EXP va|Tot EXP va";

	String webconsole_configurationFactory_nameHint() default "Bridge EDMI [{id}]";
}
