package io.openems.edge.bms.core.serial;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Dictionary;
import java.util.Hashtable;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

public class BmsSerialBridgeRepositoryTest {

	private ConfigurationAdmin admin;
	private BmsSerialBridgeRepository repository;

	@Before
	public void setUp() throws Exception {
		this.admin = mock(ConfigurationAdmin.class);
		this.repository = new BmsSerialBridgeRepository();
		var field = BmsSerialBridgeRepository.class.getDeclaredField("configurationAdmin");
		field.setAccessible(true);
		field.set(this.repository, this.admin);
	}

	@Test
	public void listsExistingThreePortsInStableOrder() throws Exception {
		var configurations = new Configuration[] {
				configuration("serial2", "/dev/ttyS2"), configuration("serial0", "/dev/ttyS0"),
				configuration("serial1", "/dev/ttyS1") };
		when(this.admin.listConfigurations(anyString())).thenReturn(configurations);
		var ports = this.repository.list();
		assertEquals(3, ports.size());
		assertEquals("serial0", ports.get(0).id());
		assertEquals("/dev/ttyS2", ports.get(2).portName());
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void updatePreservesPhysicalPortAndUnrelatedSettings() throws Exception {
		var configuration = configuration("serial0", "/dev/ttyS0");
		when(this.admin.listConfigurations(anyString())).thenReturn(new Configuration[] { configuration });
		this.repository.update("serial0", "New alias", 19200, 8, "EVEN", "ONE");
		ArgumentCaptor<Dictionary> captured = ArgumentCaptor.forClass(Dictionary.class);
		verify(configuration).update(captured.capture());
		var updated = captured.getValue();
		assertEquals("/dev/ttyS0", updated.get("portName"));
		assertEquals(false, updated.get("enabled"));
		assertEquals("retained", updated.get("customSetting"));
		assertEquals(19200, updated.get("baudRate"));
		assertEquals(9600, configuration.getProperties().get("baudRate"));
	}

	@Test
	public void missingPortsAreNotCreated() throws Exception {
		assertEquals(0, this.repository.list().size());
		try {
			this.repository.update("missing", "", 9600, 8, "NONE", "ONE");
			org.junit.Assert.fail("Expected unknown bridge rejection");
		} catch (IllegalArgumentException expected) {
			assertFalse(expected.getMessage().isEmpty());
		}
	}

	private static Configuration configuration(String id, String port) {
		var configuration = mock(Configuration.class);
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("id", id);
		properties.put("portName", port);
		properties.put("enabled", false);
		properties.put("baudRate", 9600);
		properties.put("databits", 8);
		properties.put("parity", "NONE");
		properties.put("stopbits", "ONE");
		properties.put("customSetting", "retained");
		when(configuration.getProperties()).thenReturn(properties);
		return configuration;
	}
}
