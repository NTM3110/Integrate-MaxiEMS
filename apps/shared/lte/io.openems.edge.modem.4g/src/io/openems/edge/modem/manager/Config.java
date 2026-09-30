package io.openems.edge.modem.manager;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
        name = "Modem 4G Manager",
        description = "Manages 4G/LTE modem connections via Quectel EC200 ECM or PPP fallback.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "modem4g0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
    String alias() default "4G Modem";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "APN", description = "Access Point Name for 4G connection (e.g., v-internet, m-wap)")
    String apn() default "v-internet";

    @AttributeDefinition(name = "Username", description = "APN username (leave empty if not required)")
    String username() default "";

    @AttributeDefinition(name = "Password", description = "APN password (leave empty if not required)")
    String password() default "";

    String webconsole_configurationFactory_nameHint() default "Modem 4G Manager [{id}]";
}
