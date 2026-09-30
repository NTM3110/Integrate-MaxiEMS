package io.openems.edge.monitoring;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
        name = "Monitoring MAXiCom",
        description = "")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "_modem.maxicom";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
    String alias() default "Monitoring MAXiCom";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Serial Number", description = "Serial Number")
    String SN() default "2601";

    @AttributeDefinition(name = "Device Name", description = "Name Device")
    String name() default "maxicom";

    String webconsole_configurationFactory_nameHint() default "Monitoring MAXiCom [{id}]";
}
