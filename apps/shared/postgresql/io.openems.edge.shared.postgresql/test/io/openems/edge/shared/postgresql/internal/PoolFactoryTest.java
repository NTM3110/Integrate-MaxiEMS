package io.openems.edge.shared.postgresql.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.edge.shared.postgresql.PostgresConnectionSettings;

public class PoolFactoryTest {

	@Test
	public void createsIndependentPoolsWithoutOpeningDatabase() {
		var factory = new PoolFactory();
		var first = factory.create(new PostgresConnectionSettings("bms", "127.0.0.1", 5432, "first", "user", "", 2));
		var second = factory.create(new PostgresConnectionSettings("meter", "127.0.0.1", 5432, "second", "user", "", 3));
		try {
			assertNotSame(first, second);
			assertTrue(first.isAvailable());
			assertTrue(second.isAvailable());
			first.close();
			assertFalse(first.isAvailable());
			assertTrue(second.isAvailable());
		} finally {
			first.close();
			second.close();
		}
	}

	@Test
	public void settingsDoNotExposePassword() {
		var settings = new PostgresConnectionSettings("bms", "localhost", 5432, "maxiems", "user", "secret", 5);
		assertFalse(settings.toString().contains("secret"));
	}
}
