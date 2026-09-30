package io.openems.edge.controller.pvinverter.limitreactivepower;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
        name = "Controller PV-Inverter Limit Reactive Power", //
        description = "Controls reactive power of all PV inverters. Reads setpoints from EVN controller or uses fixed values.")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlPvInverterLimitReactivePower0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Meter-ID", description = "ID of the Grid Connection Point Meter")
    String meter_id() default "meter0";

    @AttributeDefinition(name = "EVN-Controller-ID", description = "ID of the EVN Modbus Controller")
    String evn_id() default "ctrlEvnModbus0";


    @AttributeDefinition(name = "Allow EVN Control", description = "If true, allow EVN to control when enabled. If false, always use local control.")
    boolean allowEvnControl() default true;

    @AttributeDefinition(name = "Use Percentage (Local)", description = "Use percentage mode when local control")
    boolean usePercent() default false;

    @AttributeDefinition(name = "Reactive Power Limit [var] (Local)", description = "Reactive power limit in var when local control")
    int reactivePowerLimit() default 0;

    @AttributeDefinition(name = "Reactive Power Limit [%] (Local)", description = "Reactive power limit as percentage when local control")
    int reactivePowerLimitPercent() default 100;

    @AttributeDefinition(name = "Deadband [%]", description = "Deadband as percentage of system capacity")
    double deadbandPercent() default 1.0;

    @AttributeDefinition(name = "Max Ramp Rate [%/Cycle]", description = "Max power change per cycle as percentage of system capacity")
    double maxRampRatePercent() default 20.0;

    @AttributeDefinition(name = "Gain Kp", description = "Proportional Gain (0.1 - 1.0)")
    double gainKp() default 0.25;

    @AttributeDefinition(name = "Input Filter Alpha", description = "Exponential smoothing factor for input measurement (0.1 = slow, 1.0 = no filter)")
    double filterAlpha() default 0.5;

    @AttributeDefinition(name = "Output Smoothing Alpha", description = "Exponential smoothing factor for output command (0.1 = slow, 1.0 = no filter)")
    double outputSmoothing() default 0.8;

    String webconsole_configurationFactory_nameHint() default "Controller PV-Inverter Limit Reactive Power [{id}]";
}
