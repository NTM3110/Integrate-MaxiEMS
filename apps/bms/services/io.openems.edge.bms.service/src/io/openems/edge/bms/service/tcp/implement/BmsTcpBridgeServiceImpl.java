package io.openems.edge.bms.service.tcp.implement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.tcp.contracts.BmsTcpBridgeService;
import io.openems.edge.bms.service.tcp.model.BmsTcpBridgeModels.TcpBridge;

/** ConfigurationAdmin adapter limited to outbound {@code Bridge.Modbus.Tcp}. */
@Component(name = "io.openems.edge.bms.core.tcp.BmsTcpBridgeRepository", service = BmsTcpBridgeService.class)
public class BmsTcpBridgeServiceImpl implements BmsTcpBridgeService {

	static final String FACTORY_PID = "Bridge.Modbus.Tcp";
	private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
	private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");

	@Reference
	private ConfigurationAdmin configurationAdmin;

	@Override
	public List<TcpBridge> list() throws Exception {
		var result = new ArrayList<TcpBridge>();
		var configurations = this.configurationAdmin
				.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + FACTORY_PID + ")");
		if (configurations != null) {
			for (var configuration : configurations) {
				var properties = configuration.getProperties();
				if (properties != null) {
					result.add(toBridge(properties));
				}
			}
		}
		result.sort(Comparator.comparing(TcpBridge::id));
		return result;
	}

	@Override
	public TcpBridge create(String id, String alias, boolean enabled, String ip, int port) throws Exception {
		validate(id, ip, port);
		if (this.configurationAdmin.listConfigurations("(id=" + escape(id) + ")") != null) {
			throw new IllegalArgumentException("Component ID already exists: " + id);
		}
		var configuration = this.configurationAdmin.createFactoryConfiguration(FACTORY_PID, "?");
		var properties = properties(id, alias, enabled, ip, port);
		try {
			configuration.update(properties);
		} catch (Exception failure) {
			try {
				configuration.delete();
			} catch (Exception rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			throw failure;
		}
		return toBridge(properties);
	}

	@Override
	public TcpBridge update(String id, String alias, boolean enabled, String ip, int port) throws Exception {
		validate(id, ip, port);
		var configuration = find(id);
		var properties = copy(configuration.getProperties());
		properties.put("alias", normalizedAlias(id, alias));
		properties.put("enabled", enabled);
		properties.put("ip", ip.trim());
		properties.put("port", port);
		configuration.update(properties);
		return toBridge(properties);
	}

	@Override
	public void delete(String id) throws Exception {
		validateId(id);
		var references = this.configurationAdmin.listConfigurations("(modbusId=" + escape(id) + ")");
		if (references != null && references.length > 0) {
			throw new IllegalStateException("TCP bridge is used by a BMS string: " + id);
		}
		find(id).delete();
	}

	private Configuration find(String id) throws Exception {
		var configurations = this.configurationAdmin.listConfigurations("(&("
				+ ConfigurationAdmin.SERVICE_FACTORYPID + "=" + FACTORY_PID + ")(id=" + escape(id) + "))");
		if (configurations == null || configurations.length != 1) {
			throw new IllegalArgumentException("Unknown TCP bridge " + id);
		}
		return configurations[0];
	}

	private static Dictionary<String, Object> properties(String id, String alias, boolean enabled, String ip, int port) {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("id", id);
		properties.put("alias", normalizedAlias(id, alias));
		properties.put("enabled", enabled);
		properties.put("ip", ip.trim());
		properties.put("port", port);
		properties.put("logVerbosity", "NONE");
		properties.put("invalidateElementsAfterReadErrors", 1);
		return properties;
	}

	private static void validate(String id, String ip, int port) {
		validateId(id);
		var host = ip == null ? "" : ip.trim();
		if (!HOST.matcher(host).matches() || host.contains("..")) {
			throw new IllegalArgumentException("A valid TCP host or IP address is required");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("TCP port must be between 1 and 65535");
		}
	}

	private static void validateId(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("Invalid TCP bridge ID");
		}
	}

	private static String normalizedAlias(String id, String alias) {
		return alias == null || alias.isBlank() ? id : alias.trim();
	}

	private static TcpBridge toBridge(Dictionary<String, Object> properties) {
		return new TcpBridge(string(properties, "id"), string(properties, "alias"), bool(properties, "enabled"),
				string(properties, "ip"), integer(properties, "port"));
	}

	private static Dictionary<String, Object> copy(Dictionary<String, Object> source) {
		Dictionary<String, Object> target = new Hashtable<>();
		if (source != null) {
			for (var keys = source.keys(); keys.hasMoreElements();) {
				var key = keys.nextElement();
				target.put(key, source.get(key));
			}
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
		return value.replace("\\", "\\5c").replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29")
				.replace("\u0000", "\\00");
	}
}
