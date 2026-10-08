package io.openems.edge.bms.service.tcp.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.junit.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

public class BmsTcpBridgeServiceImplTest {

	@Test
	public void updatePreservesUnownedPropertiesAndCreateUsesRuntimeDefaults() throws Exception {
		var admin = new Admin();
		var existing = admin.add("Bridge.Modbus.Tcp", values("modbus3", "old.example", 502));
		existing.values.put("custom", "preserved");
		var service = service(admin);

		var updated = service.update("modbus3", "UPS", false, "new.example", 1502);
		assertEquals("UPS", updated.alias());
		assertEquals("preserved", existing.values.get("custom"));

		var created = service.create("modbus4", "Remote", true, "192.168.1.20", 502);
		assertEquals("modbus4", created.id());
		var createdConfig = admin.configurations.get(1);
		assertEquals("NONE", createdConfig.values.get("logVerbosity"));
		assertEquals(1, createdConfig.values.get("invalidateElementsAfterReadErrors"));
	}

	@Test
	public void deleteRejectsReferencedBridge() throws Exception {
		var admin = new Admin();
		var bridge = admin.add("Bridge.Modbus.Tcp", values("modbus3", "ups.local", 502));
		var reference = new Hashtable<String, Object>();
		reference.put("id", "bmsHuasuTc-str1");
		reference.put("modbusId", "modbus3");
		admin.add("BMS.Huasu.TC", reference);

		assertThrows(IllegalStateException.class, () -> service(admin).delete("modbus3"));
		assertFalse(bridge.deleted);
	}

	private static BmsTcpBridgeServiceImpl service(Admin admin) throws Exception {
		var service = new BmsTcpBridgeServiceImpl();
		var field = BmsTcpBridgeServiceImpl.class.getDeclaredField("configurationAdmin");
		field.setAccessible(true);
		field.set(service, admin.proxy());
		return service;
	}

	private static Hashtable<String, Object> values(String id, String ip, int port) {
		var values = new Hashtable<String, Object>();
		values.put("id", id);
		values.put("alias", id);
		values.put("enabled", true);
		values.put("ip", ip);
		values.put("port", port);
		return values;
	}

	private static final class Admin {
		private final List<Entry> configurations = new ArrayList<>();

		private Entry add(String factory, Hashtable<String, Object> values) {
			var entry = new Entry(factory, values);
			this.configurations.add(entry);
			return entry;
		}

		private ConfigurationAdmin proxy() {
			return (ConfigurationAdmin) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { ConfigurationAdmin.class }, (proxy, method, args) -> switch (method.getName()) {
					case "createFactoryConfiguration" -> add((String) args[0], new Hashtable<>()).proxy();
					case "listConfigurations" -> list((String) args[0]);
					default -> null;
					});
		}

		private Configuration[] list(String filter) {
			var result = this.configurations.stream().filter(entry -> !entry.deleted).filter(entry -> {
				if (filter.contains(ConfigurationAdmin.SERVICE_FACTORYPID + "=")) {
					var factory = between(filter, ConfigurationAdmin.SERVICE_FACTORYPID + "=", ")");
					if (!entry.factory.equals(factory)) {
						return false;
					}
				}
				if (filter.contains("(id=")) {
					return between(filter, "(id=", ")").equals(entry.values.get("id"));
				}
				if (filter.contains("(modbusId=")) {
					return between(filter, "(modbusId=", ")").equals(entry.values.get("modbusId"));
				}
				return true;
			}).map(Entry::proxy).toArray(Configuration[]::new);
			return result.length == 0 ? null : result;
		}

		private static String between(String value, String prefix, String suffix) {
			var start = value.indexOf(prefix) + prefix.length();
			return value.substring(start, value.indexOf(suffix, start));
		}
	}

	private static final class Entry {
		private final String factory;
		private Hashtable<String, Object> values;
		private boolean deleted;

		private Entry(String factory, Hashtable<String, Object> values) {
			this.factory = factory;
			this.values = values;
		}

		private Configuration proxy() {
			return (Configuration) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { Configuration.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getProperties" -> this.values;
					case "update" -> {
						this.values = copy((Dictionary<String, Object>) args[0]);
						yield null;
					}
					case "delete" -> {
						this.deleted = true;
						yield null;
					}
					default -> null;
					});
		}

		private static Hashtable<String, Object> copy(Dictionary<String, Object> source) {
			var copy = new Hashtable<String, Object>();
			for (var keys = source.keys(); keys.hasMoreElements();) {
				var key = keys.nextElement();
				copy.put(key, source.get(key));
			}
			return copy;
		}
	}
}
