package io.openems.edge.bridge.edmi.csvinjector;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Bridge EDMI CSV Profile Injector", //
        description = "Injects EDMI profile data from CSV into InfluxDB for testing energy separation")
@interface Config {

    @AttributeDefinition(name = "Component ID", description = "Unique ID of this component")
    String id() default "csvInjector0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name")
    String alias() default "EDMI CSV Injector";

    @AttributeDefinition(name = "Enabled", description = "Enable this component")
    boolean enabled() default true;

    @AttributeDefinition(name = "CSV Path", description = "Absolute path to the CSV file")
    String csvPath() default "C:/demo/reformatted_demo_lp_2025_01_to_26.csv";

    @AttributeDefinition(name = "Injection Delay (ms)", description = "Delay between row injections in milliseconds (200 = 0.2s)")
    int injectionDelayMs() default 200;

    @AttributeDefinition(name = "Query Delay (ms)", description = "Delay after synchronous profile writes before querying/calculating")
    int queryDelayMs() default 500;

    @AttributeDefinition(name = "Timestamp Offset (minutes)", description = "Adds this offset to every CSV timestamp; change it before reruns to avoid overwriting old Influx points")
    long timestampOffsetMinutes() default 0;


    String webconsole_configurationFactory_nameHint() default "Bridge EDMI CSV Profile Injector [{id}]";
}
