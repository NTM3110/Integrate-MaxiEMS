package io.openems.edge.controller.servicemanager;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Controller Service Manager",
    description = "Manages multiple service configurations by enabling/disabling groups of components"
)
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlServiceManager0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(
        name = "Service Mode",
        description = "Select which service configuration to activate. Use 'Custom' for user-defined service."
    )
    ServiceMode serviceMode() default ServiceMode.MAXIEMS;

    @AttributeDefinition(
        name = "Custom Service Components",
        description = "Comma-separated list of component factory PIDs for custom mode (e.g., Bridge.Dlms,Meter.Landis)"
    )
    String customComponentList() default "";

    // DLMS-specific configuration fields
    @AttributeDefinition(
        name = "DLMS COM Ports",
        description = "Comma-separated list of COM ports for DLMS bridges (e.g., COM1,COM2,COM3)"
    )
    String dlmsComPorts() default "COM1";

    @AttributeDefinition(
        name = "DLMS Meter Addresses",
        description = "Comma-separated list of device addresses for DLMS meters (e.g., 1,2,3)"
    )
    String dlmsMeterAddresses() default "1";

    @AttributeDefinition(
        name = "DLMS Meter Bridge Assignments",
        description = "Comma-separated list of bridge indices for each meter (e.g., 0,1,2 means meter0->bridge0, meter1->bridge1)"
    )
    String dlmsMeterBridgeAssignments() default "0";

    @AttributeDefinition(
        name = "Action",
        description = "Action to perform when applying service configuration"
    )
    ServiceAction action() default ServiceAction.APPLY;

    String webconsole_configurationFactory_nameHint() default "Controller Service Manager [{id}]";
}

enum ServiceMode {
    MAXIEMS,
    AT_DIGITAL_METER_DLMS,
    CUSTOM
}

enum ServiceAction {
    APPLY,
    ENABLE_ALL,
    DISABLE_ALL,
    TOGGLE
}