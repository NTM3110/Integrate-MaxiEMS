package io.openems.edge.bms.service.persistence.implement;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.Test;

public class BmsDatabaseCompatibilityTest {

	@Test
	public void legacyConfigurationMapsToSharedPoolSettings() {
		var settings = BmsDatabaseImpl.legacySettings(config(Map.of("pgHost", "db-host", "pgPort", 5433,
				"database", "bms_db", "pgUser", "operator", "pgPassword", "password", "connectionPoolSize", 7)));
		assertEquals("bms", settings.id());
		assertEquals("db-host", settings.host());
		assertEquals(5433, settings.port());
		assertEquals("bms_db", settings.database());
		assertEquals("operator", settings.user());
		assertEquals("password", settings.password());
		assertEquals(7, settings.poolSize());
	}

	private static Config config(Map<String, Object> values) {
		return (Config) Proxy.newProxyInstance(Config.class.getClassLoader(), new Class<?>[] { Config.class },
				(proxy, method, args) -> values.get(method.getName()));
	}
}
