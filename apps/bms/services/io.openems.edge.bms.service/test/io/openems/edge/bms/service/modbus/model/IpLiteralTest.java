package io.openems.edge.bms.service.modbus.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class IpLiteralTest {
	@Test
	public void acceptsIpv4AndIpv6LiteralsWithoutHostnames() {
		assertEquals("0.0.0.0", IpLiteral.parse("0.0.0.0").getHostAddress());
		assertEquals("192.168.4.127", IpLiteral.parse("192.168.4.127").getHostAddress());
		assertEquals("0:0:0:0:0:0:0:0", IpLiteral.parse("::").getHostAddress());
		assertThrows(IllegalArgumentException.class, () -> IpLiteral.parse("localhost"));
		assertThrows(IllegalArgumentException.class, () -> IpLiteral.parse("192.168.4.999"));
	}
}
