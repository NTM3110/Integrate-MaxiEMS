package io.openems.edge.controller.pvinverter.limitbatterysoc;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Controller Inverter Limit Battery SOC", //
        description = "Defines maximum charge and discharge SOC limits for hybrid inverter with battery.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlInverterLimitBatterySOC0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Inverter-ID", description = "ID of Inverter device.")
    String pvInverter_id();

    @AttributeDefinition(name = "Max Charge SOC [%]", description = "Maximum SOC for charging (0-100). Battery will stop charging above this SOC.")
    int maxChargeSOC() default 80;

    @AttributeDefinition(name = "Max Discharge SOC [%]", description = "Minimum SOC for discharging (0-100). Battery will stop discharging below this SOC.")
    int maxDischargeSOC() default 20;

    String webconsole_configurationFactory_nameHint() default "Controller Inverter Limit Battery SOC [{id}]";
}
