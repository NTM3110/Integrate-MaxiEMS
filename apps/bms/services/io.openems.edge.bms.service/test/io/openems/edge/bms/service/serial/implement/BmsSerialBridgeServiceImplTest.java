package io.openems.edge.bms.service.serial.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

public class BmsSerialBridgeServiceImplTest {
	@Test
	public void batchBridgeTimeoutRoundTripsAndLegacyBridgeRejectsIt() throws Exception {
		var admin = mock(ConfigurationAdmin.class);
		var batch = configuration("Bridge.BmsModbus.Serial", "modbus0");
		var legacy = configuration("Bridge.Modbus.Serial", "modbus1");
		when(admin.listConfigurations(anyString())).thenAnswer(call -> {
			String filter = call.getArgument(0);
			if (filter.contains("(id=modbus0)")) return new Configuration[] {batch};
			if (filter.contains("(id=modbus1)")) return new Configuration[] {legacy};
			return new Configuration[] {batch, legacy};
		});
		var service = new BmsSerialBridgeServiceImpl();
		var field = BmsSerialBridgeServiceImpl.class.getDeclaredField("configurationAdmin");
		field.setAccessible(true);
		field.set(service, admin);

		assertTrue(service.list().get(0).batchAcquisition());
		assertEquals(100, service.list().get(0).responseTimeoutMs());
		assertFalse(service.list().get(1).batchAcquisition());
		assertEquals(750, service.update("modbus0", "BMS", 9600, 8, "NONE", "ONE", 750)
				.responseTimeoutMs());
		assertEquals(750, service.list().get(0).responseTimeoutMs());
		assertThrows(IllegalArgumentException.class,
				() -> service.update("modbus0", "BMS", 9600, 8, "NONE", "ONE", 10_001));
		assertThrows(IllegalArgumentException.class,
				() -> service.update("modbus1", "Legacy", 9600, 8, "NONE", "ONE", 100));
	}

	private static Configuration configuration(String factory, String id) throws Exception {
		var configuration = mock(Configuration.class);
		var values = new AtomicReference<Dictionary<String, Object>>();
		var initial = new Hashtable<String, Object>();
		initial.put("id", id);
		initial.put("alias", id);
		initial.put("enabled", true);
		initial.put("portName", "/dev/ttyS1");
		initial.put("baudRate", 9600);
		initial.put("databits", 8);
		initial.put("parity", "NONE");
		initial.put("stopbits", "ONE");
		values.set(initial);
		when(configuration.getFactoryPid()).thenReturn(factory);
		when(configuration.getProperties()).thenAnswer(call -> values.get());
		doAnswer(call -> {
			values.set(call.getArgument(0));
			return null;
		}).when(configuration).update(any());
		return configuration;
	}
}
