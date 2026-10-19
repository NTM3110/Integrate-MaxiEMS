package io.openems.edge.bms.service.serial.implement;

import io.openems.edge.bms.service.serial.contracts.BmsSerialBridgeService;
import io.openems.edge.bms.service.serial.model.BmsSerialBridgeModels.SerialBridge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;


/** Reads and updates the native OpenEMS Modbus/RTU bridge configurations. */
@Component(name = "io.openems.edge.bms.core.serial.BmsSerialBridgeRepository", service = BmsSerialBridgeService.class)
public class BmsSerialBridgeServiceImpl implements BmsSerialBridgeService {

	static final String LEGACY_FACTORY_PID = "Bridge.Modbus.Serial";
	static final String BMS_FACTORY_PID = "Bridge.BmsModbus.Serial";
	static final String RESERVED_BY = "maxiems.modbusServerReservedBy";

	@Reference
	private ConfigurationAdmin configurationAdmin;

	@Override
	public List<SerialBridge> list() throws Exception {
		var configurations = this.configurationAdmin.listConfigurations(factoryFilter());
		var result = new ArrayList<SerialBridge>();
		if (configurations != null) for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (properties != null) result.add(toBridge(configuration));
		}
		result.sort(Comparator.comparing(SerialBridge::id));
		return result;
	}

	@Override
	public SerialBridge update(String id, String alias, int baudRate, int databits, String parity, String stopbits,
			Integer responseTimeoutMs)
			throws Exception {
		var configuration = this.find(id);
		var batchAcquisition = BMS_FACTORY_PID.equals(configuration.getFactoryPid());
		if (responseTimeoutMs != null && !batchAcquisition) {
			throw new IllegalArgumentException("Response timeout is available only on a BMS batch bridge");
		}
		if (responseTimeoutMs != null && (responseTimeoutMs < 10 || responseTimeoutMs > 10_000)) {
			throw new IllegalArgumentException("Response timeout must be between 10 and 10000 ms");
		}
		if (!string(configuration.getProperties(), RESERVED_BY).isBlank()) {
			throw new IllegalStateException("Serial port is reserved by the BMS Modbus Server");
		}
		var properties = copy(configuration.getProperties());
		properties.put("alias", alias == null ? "" : alias);
		properties.put("baudRate", baudRate);
		properties.put("databits", databits);
		properties.put("parity", parity);
		properties.put("stopbits", stopbits);
		if (responseTimeoutMs != null) properties.put("responseTimeoutMs", responseTimeoutMs);
		configuration.update(properties);
		return toBridge(configuration);
	}

	private Configuration find(String id) throws Exception {
		var configurations = this.configurationAdmin.listConfigurations("(&" + factoryFilter()
				+ "(id=" + escape(id) + "))");
		if (configurations == null || configurations.length != 1) throw new IllegalArgumentException("Unknown serial bridge " + id);
		return configurations[0];
	}

	private static String factoryFilter() {
		return "(|(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + LEGACY_FACTORY_PID + ")("
				+ ConfigurationAdmin.SERVICE_FACTORYPID + "=" + BMS_FACTORY_PID + "))";
	}

	private static SerialBridge toBridge(Configuration configuration) {
		var properties = configuration.getProperties();
		var batchAcquisition = BMS_FACTORY_PID.equals(configuration.getFactoryPid());
		return new SerialBridge(string(properties, "id"), string(properties, "alias"), bool(properties, "enabled"),
				string(properties, "portName"), integer(properties, "baudRate"), integer(properties, "databits"),
				string(properties, "parity"), string(properties, "stopbits"), batchAcquisition,
				integer(properties, "responseTimeoutMs", batchAcquisition ? 100 : 1000));
	}

	private static Dictionary<String, Object> copy(Dictionary<String, Object> source) {
		Dictionary<String, Object> target = new Hashtable<>();
		if (source != null) for (var keys = source.keys(); keys.hasMoreElements();) {
			var key = keys.nextElement();
			target.put(key, source.get(key));
		}
		return target;
	}

	private static String string(Dictionary<String, Object> properties, String key) {
		var value = properties.get(key);
		return value == null ? "" : value.toString();
	}

	private static int integer(Dictionary<String, Object> properties, String key) {
		var value = properties.get(key);
		return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
	}

	private static int integer(Dictionary<String, Object> properties, String key, int fallback) {
		var value = properties.get(key);
		return value == null ? fallback : value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
	}

	private static boolean bool(Dictionary<String, Object> properties, String key) {
		var value = properties.get(key);
		return value instanceof Boolean bool ? bool.booleanValue() : Boolean.parseBoolean(String.valueOf(value));
	}

	private static String escape(String value) {
		return value.replace("\\", "\\5c").replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29").replace("\u0000", "\\00");
	}
}
