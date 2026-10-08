package io.openems.edge.bms.service.time.implement;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;
import io.openems.common.exceptions.OpenemsException;

public class BmsTimeServiceImplTest {
	@Test
	public void acceptsOnlyLiteralAddresses() throws Exception {
		for (var address : new String[] {"192.168.127.8", "::1", "2001:db8::1"}) {
			BmsTimeServiceImpl.validateIp(address);
		}
		for (var address : new String[] {null, "", "localhost", "1.2.3", "256.1.1.1", "01.2.3.4",
				"1.2.3.4\n", "::1%eth0", "[::1]", "::gg", "1.2.3.4;reboot"}) {
			assertThrows(OpenemsException.class, () -> BmsTimeServiceImpl.validateIp(address));
		}
	}

	@Test
	public void commandUsesSeparateValidatedArgument() throws Exception {
		var service = new BmsTimeServiceImpl() {
			@Override
			protected String run(String... command) {
				assertArrayEquals(new String[] {"/usr/bin/sudo", "-n", "/usr/local/sbin/maxiems-time-helper",
						"configure", "192.168.127.8"}, command);
				return "{\"synchronized\":false,\"ntpServiceActive\":true,"
						+ "\"currentTime\":\"2026-10-06T14:00:00+07:00\",\"serverReachable\":false,"
						+ "\"packetCount\":0,\"pollInterval\":\"34min 8s\",\"error\":null}";
			}
		};
		var result = service.configure("192.168.127.8");
		assertEquals(Boolean.FALSE, result.synchronizedClock());
		assertEquals(Boolean.TRUE, result.ntpServiceActive());
		assertEquals(Boolean.FALSE, result.serverReachable());
		assertEquals(Long.valueOf(0), result.packetCount());
		assertEquals("2026-10-06T14:00:00+07:00", result.currentTime());
		assertNull(result.serverAddress());
		assertThrows(OpenemsException.class, () -> service.configure("bad address"));
	}

	@Test
	public void malformedOutputIsRejected() {
		assertThrows(OpenemsException.class, () -> BmsTimeServiceImpl.parse("error"));
		assertThrows(OpenemsException.class, () -> BmsTimeServiceImpl.parse("{\"synchronized\":\"no\"}"));
	}
}
