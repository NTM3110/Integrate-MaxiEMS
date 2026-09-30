package io.openems.edge.controller.energy.calculator;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Controller Energy Calculator", description = "Performs energy calculations based on profile readings from InfluxDB")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlEnergyCalc0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    // InfluxDB settings
    @AttributeDefinition(name = "InfluxDB URL", description = "URL of the InfluxDB instance")
    String influxUrl() default "http://localhost:8086";

    @AttributeDefinition(name = "InfluxDB Organization", description = "InfluxDB organization")
    String influxOrg() default "openems";

    @AttributeDefinition(name = "InfluxDB API Key", description = "InfluxDB API key")
    String influxApiKey() default "";

    @AttributeDefinition(name = "InfluxDB Bucket", description = "InfluxDB bucket for data")
    String influxBucket() default "db";

    @AttributeDefinition(name = "Query Language", description = "Query language for InfluxDB (FLUX or INFLUXQL)")
    io.openems.shared.influxdb.QueryLanguageConfig queryLanguage() default io.openems.shared.influxdb.QueryLanguageConfig.FLUX;

    @AttributeDefinition(name = "Read Only", description = "Is the InfluxDB connection read-only?")
    boolean isReadOnly() default false;

    @AttributeDefinition(name = "Max Queue Size", description = "Maximum queue size for InfluxDB writes")
    int maxQueueSize() default 1000;

    @AttributeDefinition(name = "Interval Minutes", description = "Interval duration in minutes for calculations")
    int intervalMinutes() default 30;

    // PostgreSQL settings
    @AttributeDefinition(name = "PostgreSQL Host", description = "PostgreSQL host")
    String postgreSqlHost() default "localhost";

    @AttributeDefinition(name = "PostgreSQL Port", description = "PostgreSQL port")
    int postgreSqlPort() default 5432;

    @AttributeDefinition(name = "PostgreSQL Database", description = "PostgreSQL database name")
    String postgreSqlDatabase() default "energy_reports";

    @AttributeDefinition(name = "PostgreSQL User", description = "PostgreSQL username")
    String postgreSqlUser() default "openems";

    @AttributeDefinition(name = "PostgreSQL Password", description = "PostgreSQL password")
    String postgreSqlPassword() default "";

    @AttributeDefinition(name = "Enable PostgreSQL", description = "Enable writing to PostgreSQL")
    boolean enablePostgreSql() default false;

    @AttributeDefinition(name = "PostgreSQL Read Only", description = "Is the PostgreSQL connection read-only?")
    boolean postgreSqlReadOnly() default false;

}