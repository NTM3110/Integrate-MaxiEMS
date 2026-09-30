package io.openems.edge.modem.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.annotation.Annotation;

import org.junit.Test;

public class Modem4GManagerImplTest {

	@Test
	public void testModifiedSubmitsHardwareWorkOnlyWhenEnabled() {
		assertFalse(Modem4GManagerImpl.shouldSubmitModifiedUpdate(config(false)));
		assertTrue(Modem4GManagerImpl.shouldSubmitModifiedUpdate(config(true)));
	}

	private static Config config(boolean enabled) {
		return new Config() {

			@Override
			public Class<? extends Annotation> annotationType() {
				return Config.class;
			}

			@Override
			public String id() {
				return "modem4g0";
			}

			@Override
			public String alias() {
				return "4G Modem";
			}

			@Override
			public boolean enabled() {
				return enabled;
			}

			@Override
			public String apn() {
				return "internet";
			}

			@Override
			public String username() {
				return "";
			}

			@Override
			public String password() {
				return "";
			}

			@Override
			public String webconsole_configurationFactory_nameHint() {
				return "Modem 4G Manager [{id}]";
			}
		};
	}
}
