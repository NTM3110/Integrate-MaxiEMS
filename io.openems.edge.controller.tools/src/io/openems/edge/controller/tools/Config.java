package io.openems.edge.controller.tools;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Controller BMS Huasu TC Bulk Creator", description = "Creates multiple BmsHuasuTc components (one per cell)")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlBmsHuasuBulk0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Number of cells / components to create")
    int numberOfCells() default 100;

    @AttributeDefinition(name = "Modbus bridge IDs to use (comma-separated, e.g. modbus0,modbus1)")
    String modbusId() default "modbus0";

    @AttributeDefinition(name = "Starting Modbus Unit-ID")
    int startUnitId() default 1;

    @AttributeDefinition(name = "String ID")
    String stringId() default "string";

    @AttributeDefinition(name = "Capacity of each cell in Ah")
    int cellCapacityAh() default 100;

    @AttributeDefinition(name = "Cutoff Voltage of each cell in V")
    double cellCutoffVoltage() default 2.5;

    @AttributeDefinition(name = "Float Voltage of each cell in V")
    double cellFloatVoltage() default 2.8;

    @AttributeDefinition(name ="Rated resistance (when the battery is completely new) in uOhm")
    int ratedResistanceUohm() default 1450;

}
