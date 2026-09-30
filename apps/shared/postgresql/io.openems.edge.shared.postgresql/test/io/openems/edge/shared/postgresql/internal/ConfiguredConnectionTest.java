package io.openems.edge.shared.postgresql.internal;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.Test;

public class ConfiguredConnectionTest {

	@Test
	public void rejectsDuplicateIdsAndReleasesIdOnDeactivate() throws Exception {
		var first = configuredConnection();
		var second = configuredConnection();
		var config = config(Map.of("id", "bms", "host", "localhost", "port", 5432, "database", "one",
				"user", "operator", "password", "", "poolSize", 2));
		first.activate(config);
		try {
			try {
				second.activate(config);
				fail("Duplicate ID should be rejected");
			} catch (IllegalStateException expected) {
				assertTrue(expected.getMessage().contains("Duplicate"));
			}
		} finally {
			first.deactivate();
		}
		second.activate(config);
		second.deactivate();
	}

	private static ConfiguredConnection configuredConnection() throws Exception {
		var connection = new ConfiguredConnection();
		var field = ConfiguredConnection.class.getDeclaredField("factory");
		field.setAccessible(true);
		field.set(connection, new PoolFactory());
		return connection;
	}

	private static Config config(Map<String, Object> values) {
		return (Config) Proxy.newProxyInstance(Config.class.getClassLoader(), new Class<?>[] { Config.class },
				(proxy, method, args) -> values.get(method.getName()));
	}
}
