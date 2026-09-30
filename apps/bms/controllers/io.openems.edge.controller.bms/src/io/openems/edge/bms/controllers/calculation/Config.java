package io.openems.edge.bms.controllers.calculation;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Controller BMS Huasu String Calculator", description = "Calculates one existing Huasu TA/TC string; TA/TC lifecycle is owned by BMS string management")
@interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "ctrlBmsHuasuBulk0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Snapshot interval", description = "Minimum seconds between published calculated snapshots")
    int snapshotIntervalSeconds() default 2;

    @AttributeDefinition(name = "Number of cells to calculate")
    int numberOfCells() default 100;

    @AttributeDefinition(name = "Modbus bridge IDs to use (comma-separated, e.g. modbus0,modbus1)")
    String modbusId() default "modbus0";

    @AttributeDefinition(name = "Starting Modbus Unit-ID")
    int startUnitId() default 1;

	@AttributeDefinition(name = "String ID")
	String stringId() default "string";

	@AttributeDefinition(name = "String key")
	String stringKey() default "str1";

    @AttributeDefinition(name = "Capacity of each cell in Ah")
    int cellCapacityAh() default 100;

    @AttributeDefinition(name = "Cutoff Voltage of each cell in V")
    double cellCutoffVoltage() default 2.5;

    @AttributeDefinition(name = "Float Voltage of each cell in V")
    double cellFloatVoltage() default 2.8;

    @AttributeDefinition(name ="Rated resistance (when the battery is completely new) in uOhm")
    int ratedResistanceUohm() default 1450;

    @AttributeDefinition(name = "Estimator mode", description = "AUTO consumes available calibration references; LEGACY is the emergency rollback; BASELINE_V1 is a compatibility alias")
    String estimatorMode() default "AUTO";

    @AttributeDefinition(name = "Discharge current direction", description = "Must match the per-string Monitoring policy")
    String dischargeCurrentDirection() default "NEGATIVE";

	@AttributeDefinition(name = "Estimator maximum current gap (seconds)")
	int estimatorMaximumGapSeconds() default 60;

	@AttributeDefinition(name = "Estimator time scale", description = "Test/demo-only multiplier applied to coulomb-counting elapsed time; keep 1.0 for production")
	double estimatorTimeScale() default 1.0;

    @AttributeDefinition(name = "Baseline reference freshness (seconds)")
    int baselineReferenceFreshnessSeconds() default 120;

	@AttributeDefinition(name = "Active calibration context freshness (seconds)")
	int activeCalibrationContextFreshnessSeconds() default 10;

	@AttributeDefinition(name = "Estimator Float current deadband (A)")
	double estimatorFloatCurrentDeadbandA() default 0.2;

	@AttributeDefinition(name = "Estimator Float voltage tolerance (V/cell)")
	double estimatorFloatVoltageToleranceV() default 0.02;

	@AttributeDefinition(name = "Estimator Float stable window (seconds)")
	int estimatorFloatStableSeconds() default 60;

	@AttributeDefinition(name = "SoC checkpoint policy", description = "DISABLED or PERSIST_DATABASE; persistence never runs on the scheduler thread")
	String socCheckpointPolicy() default "DISABLED";

}
