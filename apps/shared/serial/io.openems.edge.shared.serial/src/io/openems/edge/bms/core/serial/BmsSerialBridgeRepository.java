package io.openems.edge.bms.core.serial;

import io.openems.edge.bms.core.serial.BmsSerialBridgeModels.SerialBridge;
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
@Component(service = BmsSerialBridgeRepository.class)
public class BmsSerialBridgeRepository {

	static final String FACTORY_PID = "Bridge.Modbus.Serial";

	@Reference
	private ConfigurationAdmin configurationAdmin;

	public List<SerialBridge> list() throws Exception {
		var configurations = this.configurationAdmin
				.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + FACTORY_PID + ")");
		var result = new ArrayList<SerialBridge>();
		if (configurations != null) for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (properties != null) result.add(toBridge(properties));
		}
		result.sort(Comparator.comparing(SerialBridge::id));
		return result;
	}

	public SerialBridge update(String id, String alias, int baudRate, int databits, String parity, String stopbits)
			throws Exception {
		var configuration = this.find(id);
		var properties = copy(configuration.getProperties());
		properties.put("alias", alias == null ? "" : alias);
		properties.put("baudRate", baudRate);
		properties.put("databits", databits);
		properties.put("parity", parity);
		properties.put("stopbits", stopbits);
		configuration.update(properties);
		return toBridge(properties);
	}

	private Configuration find(String id) throws Exception {
		var configurations = this.configurationAdmin.listConfigurations("(&(" + ConfigurationAdmin.SERVICE_FACTORYPID + "="
				+ FACTORY_PID + ")(id=" + escape(id) + "))");
		if (configurations == null || configurations.length != 1) throw new IllegalArgumentException("Unknown serial bridge " + id);
		return configurations[0];
	}

	private static SerialBridge toBridge(Dictionary<String, Object> properties) {
		return new SerialBridge(string(properties, "id"), string(properties, "alias"), bool(properties, "enabled"),
				string(properties, "portName"), integer(properties, "baudRate"), integer(properties, "databits"),
				string(properties, "parity"), string(properties, "stopbits"));
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

	private static boolean bool(Dictionary<String, Object> properties, String key) {
		var value = properties.get(key);
		return value instanceof Boolean bool ? bool.booleanValue() : Boolean.parseBoolean(String.valueOf(value));
	}

	private static String escape(String value) {
		return value.replace("\\", "\\5c").replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29").replace("\u0000", "\\00");
	}
}
