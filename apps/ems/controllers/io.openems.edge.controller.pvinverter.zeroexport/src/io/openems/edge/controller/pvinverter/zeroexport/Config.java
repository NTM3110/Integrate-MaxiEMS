package io.openems.edge.controller.pvinverter.zeroexport;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller PV-Inverter Zero Export", //
		description = "Controls PV inverter active power to maintain the configured grid setpoint, with zero export as the default.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlPvInverterZeroExport0";

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

	    @AttributeDefinition(name = "Use Percentage (Local)", description = "If true, use a P-out percentage target when local control is active")
	    boolean usePercentage() default false;

	    @AttributeDefinition(name = "P-out Target [W] (Local)", description = "Target power exported at the grid connection point when local control is active; zero prevents export")
	    int powerLimit() default 0;

	    @AttributeDefinition(name = "P-out Target [%] (Local)", description = "Target exported power as percentage of PV capacity when local control is active")
	    int powerLimitPercent() default 100;

    @AttributeDefinition(name = "Deadband [%]", description = "Deadband as percentage of system capacity")
    double deadbandPercent() default 1.0;

    @AttributeDefinition(name = "Max Ramp Rate [%/Cycle]", description = "Max power change per cycle as percentage of system capacity")
    double maxRampRatePercent() default 20.0;

    @AttributeDefinition(name = "Gain Kp", description = "Proportional Gain (0.1 - 1.0)")
    double gainKp() default 0.25;

    @AttributeDefinition(name = "Gain Ki", description = "Integral Gain (e.g. 0.001 - 0.05). Set 0 to disable integral (pure P mode).")
    double gainKi() default 0.005;

    @AttributeDefinition(name = "Anti-windup Gain Kaw", description = "Back-calculation anti-windup gain (0 = disabled, typical 0.1 - 1.0)")
    double gainKaw() default 0.1;

    @AttributeDefinition(name = "Input Filter Alpha", description = "Exponential smoothing factor for input measurement (0.1 = slow, 1.0 = no filter)")
    double filterAlpha() default 0.5;

    @AttributeDefinition(name = "Output Smoothing Alpha", description = "Exponential smoothing factor for output command (0.1 = slow, 1.0 = no filter)")
    double outputSmoothing() default 0.8;

	String webconsole_configurationFactory_nameHint() default "Controller PV-Inverter Zero Export [{id}]";
}
