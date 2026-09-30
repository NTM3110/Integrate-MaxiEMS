package io.openems.edge.network.manager.operation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import io.openems.edge.network.manager.Mode;

public class NetworkConfigValidatorTest {

	@Test
	public void acceptsDhcpAndValidStaticConfiguration() {
		var validator = new NetworkConfigValidator();

		assertEquals(new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of()),
				validator.validate(new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of())));
		assertEquals(new NetworkInterfaceConfig("eth1", Mode.STATIC, "10.0.0.5", 24, "10.0.0.1",
				List.of("8.8.8.8")), validator.validate(new NetworkInterfaceConfig("eth1", Mode.STATIC,
						"10.0.0.5", 24, "10.0.0.1", List.of("8.8.8.8"))));
	}

	@Test
	public void rejectsInvalidInterfaceAddressPrefixGatewayAndDns() {
		var validator = new NetworkConfigValidator();

		assertError(NetworkErrorCode.INVALID_INTERFACE, () -> validator
				.validate(new NetworkInterfaceConfig("eth0;reboot", Mode.DHCP, null, null, null, List.of())));
		assertError(NetworkErrorCode.INVALID_IP_ADDRESS, () -> validator
				.validate(new NetworkInterfaceConfig("eth0", Mode.STATIC, "999.1.1.1", 24, null, List.of())));
		assertError(NetworkErrorCode.INVALID_PREFIX_LENGTH, () -> validator
				.validate(new NetworkInterfaceConfig("eth0", Mode.STATIC, "10.0.0.5", 33, null, List.of())));
		assertError(NetworkErrorCode.INVALID_GATEWAY, () -> validator.validate(
				new NetworkInterfaceConfig("eth0", Mode.STATIC, "10.0.0.5", 24, "gateway", List.of())));
		assertError(NetworkErrorCode.INVALID_DNS, () -> validator.validate(
				new NetworkInterfaceConfig("eth0", Mode.STATIC, "10.0.0.5", 24, null, List.of("8.8.8.8;id"))));
	}

	private static void assertError(NetworkErrorCode expected, Runnable action) {
		var error = assertThrows(NetworkValidationException.class, action::run);
		assertEquals(expected, error.code());
	}
}
