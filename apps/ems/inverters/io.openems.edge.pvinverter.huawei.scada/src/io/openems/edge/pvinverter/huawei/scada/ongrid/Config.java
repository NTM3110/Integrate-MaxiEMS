package io.openems.edge.pvinverter.huawei.scada.ongrid;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "PV-Inverter Huawei SCADA", description = "Huawei SUN2000 SCADA register collection.")
@interface Config {
	@AttributeDefinition(name = "Component-ID") String id() default "PvInverterHuaweiOnGrid0";
	@AttributeDefinition(name = "Alias") String alias() default "";
	@AttributeDefinition(name = "Is enabled?") boolean enabled() default true;
	@AttributeDefinition(name = "Modbus-ID") String modbus_id() default "modbus0";
	@AttributeDefinition(name = "Modbus Unit-ID") int modbusUnitId();
	@AttributeDefinition(name = "Modbus target filter") String Modbus_target() default "(enabled=true)";
	String webconsole_configurationFactory_nameHint() default "PV-Inverter Huawei SCADA [{id}]";
}
