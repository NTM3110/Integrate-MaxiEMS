package io.openems.edge.bms.service.modbus.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;
import io.openems.edge.bms.service.modbus.model.IpLiteral;

public class BmsModbusServerServiceImplTest {

	@Test
	public void serialReservationIsReleasedWhenReturningToTcp() throws Exception {
		var server = configuration(BmsModbusServerServiceImpl.SERVER_FACTORY, values(
				"id", BmsModbusServerServiceImpl.SERVER_ID, "enabled", false, "port", 1502, "unitId", 1));
		var port0 = configuration(BmsModbusServerServiceImpl.SERIAL_FACTORY, bridge("modbus0", "/dev/ttyS1"));
		var port1 = configuration(BmsModbusServerServiceImpl.SERIAL_FACTORY, bridge("modbus1", "/dev/ttyS7"));
		var port2 = configuration(BmsModbusServerServiceImpl.SERIAL_FACTORY, bridge("modbus2", "/dev/ttyS9"));
		var admin = mock(ConfigurationAdmin.class);
		when(admin.listConfigurations(any())).thenAnswer(invocation -> {
			var filter = (String) invocation.getArgument(0);
			if (filter.contains(BmsModbusServerServiceImpl.SERVER_FACTORY)) return new Configuration[] { server.configuration };
			if (filter.contains(BmsModbusServerServiceImpl.SERIAL_FACTORY)) return new Configuration[] { port0.configuration, port1.configuration, port2.configuration };
			if (filter.equals("(modbusId=*)")) return null;
			return null;
		});
		var service = new BmsModbusServerServiceImpl();
		var field = BmsModbusServerServiceImpl.class.getDeclaredField("configurationAdmin");
		field.setAccessible(true);
		field.set(service, admin);

		var serial = service.update(true, Transport.SERIAL_RTU, 7, "0.0.0.0", 1502, "modbus1");
		assertEquals(Transport.SERIAL_RTU, serial.transport());
		assertEquals("modbus1", serial.serialPortId());
		assertFalse(bool(port1.properties.get(), "enabled"));
		assertEquals(BmsModbusServerServiceImpl.SERVER_ID,
				port1.properties.get().get(BmsModbusServerServiceImpl.RESERVED_BY));
		assertTrue(bool(port1.properties.get(), BmsModbusServerServiceImpl.PREVIOUS_ENABLED));
		assertEquals(1502, ((Number) server.properties.get().get("port")).intValue());
		assertEquals("/dev/ttyS7", server.properties.get().get("portName"));

		var tcp = service.update(true, Transport.TCP, 3, "192.168.4.127", 2502, "");
		assertEquals(Transport.TCP, tcp.transport());
		assertTrue(bool(port1.properties.get(), "enabled"));
		assertNull(port1.properties.get().get(BmsModbusServerServiceImpl.RESERVED_BY));
		assertNull(port1.properties.get().get(BmsModbusServerServiceImpl.PREVIOUS_ENABLED));
		assertEquals(2502, tcp.tcpPort());
		assertEquals("192.168.4.127", tcp.bindAddress());
	}

	@Test
	public void serialRtuDoesNotRequireHiddenTcpFields() throws Exception {
		var fixture = fixture();
		var serial = fixture.service.update(true, Transport.SERIAL_RTU, 1, "", 0, "modbus0");
		assertEquals("0.0.0.0", serial.bindAddress());
		assertEquals(1502, serial.tcpPort());
		assertEquals(Transport.SERIAL_RTU, serial.transport());
	}

	@Test
	public void bindAddressAcceptsIpLiteralsAndRejectsHostnames() {
		assertEquals("0.0.0.0", IpLiteral.parse("0.0.0.0").getHostAddress());
		assertTrue(IpLiteral.parse("2001:db8::1").getHostAddress().contains("2001:db8"));
		assertThrows(IllegalArgumentException.class, () -> IpLiteral.parse("localhost"));
		assertThrows(IllegalArgumentException.class, () -> IpLiteral.parse("192.168.4.999"));
	}

	private static Fixture fixture() throws Exception {
		var server = configuration(BmsModbusServerServiceImpl.SERVER_FACTORY, values(
				"id", BmsModbusServerServiceImpl.SERVER_ID, "enabled", false, "port", 1502, "unitId", 1));
		var port0 = configuration(BmsModbusServerServiceImpl.SERIAL_FACTORY, bridge("modbus0", "/dev/ttyS1"));
		var port1 = configuration(BmsModbusServerServiceImpl.SERIAL_FACTORY, bridge("modbus1", "/dev/ttyS7"));
		var port2 = configuration(BmsModbusServerServiceImpl.SERIAL_FACTORY, bridge("modbus2", "/dev/ttyS9"));
		var admin = mock(ConfigurationAdmin.class);
		when(admin.listConfigurations(any())).thenAnswer(invocation -> {
			var filter = (String) invocation.getArgument(0);
			if (filter.contains(BmsModbusServerServiceImpl.SERVER_FACTORY)) return new Configuration[] { server.configuration };
			if (filter.contains(BmsModbusServerServiceImpl.SERIAL_FACTORY)) return new Configuration[] { port0.configuration, port1.configuration, port2.configuration };
			return null;
		});
		var service = new BmsModbusServerServiceImpl();
		var field = BmsModbusServerServiceImpl.class.getDeclaredField("configurationAdmin");
		field.setAccessible(true);
		field.set(service, admin);
		return new Fixture(service);
	}

	private static Entry configuration(String factory, Dictionary<String, Object> initial) throws Exception {
		var configuration = mock(Configuration.class);
		var properties = new AtomicReference<>(copy(initial));
		when(configuration.getFactoryPid()).thenReturn(factory);
		when(configuration.getProperties()).thenAnswer(ignored -> copy(properties.get()));
		doAnswer(invocation -> { properties.set(copy(invocation.getArgument(0))); return null; }).when(configuration).update(any());
		return new Entry(configuration, properties);
	}

	private static Dictionary<String, Object> bridge(String id, String path) {
		return values("id", id, "enabled", true, "portName", path, "baudRate", 9600, "databits", 8,
				"parity", "NONE", "stopbits", "ONE");
	}

	private static Dictionary<String, Object> values(Object... values) {
		Dictionary<String, Object> result = new Hashtable<>();
		for (var i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
		return result;
	}

	private static Dictionary<String, Object> copy(Dictionary<String, Object> source) {
		Dictionary<String, Object> result = new Hashtable<>();
		for (var keys = source.keys(); keys.hasMoreElements();) { var key = keys.nextElement(); result.put(key, source.get(key)); }
		return result;
	}

	private static boolean bool(Dictionary<String, Object> properties, String key) {
		var value = properties.get(key);
		return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
	}

	private record Entry(Configuration configuration, AtomicReference<Dictionary<String, Object>> properties) {}
	private record Fixture(BmsModbusServerServiceImpl service) {}
}
