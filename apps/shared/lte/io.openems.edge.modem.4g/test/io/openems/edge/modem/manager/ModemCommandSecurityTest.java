package io.openems.edge.modem.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

public class ModemCommandSecurityTest {

	@Test
	public void testPppCredentialsAreOnlyPassedToRestrictedHelperViaStdin() {
		var apn = "internet; touch /tmp/pwn # `x` $(x) \"x\" \\x 'quote'";
		var command = ModemCommandBuilder.buildPppConfigCommand(apn, "user name", "pass;$(bad)");
		var encodedValues = command.stdinAsString().lines().toList();

		assertEquals("sudo /usr/local/sbin/openems-update-modem-ppp", command.shellCommand());
		assertEquals(apn, decode(encodedValues.get(0)));
		assertEquals("user name", decode(encodedValues.get(1)));
		assertEquals("pass;$(bad)", decode(encodedValues.get(2)));
		assertFalse(command.shellCommand().contains(apn));
		assertFalse(command.shellCommand().contains("pass;$(bad)"));
	}

	@Test
	public void testAtCredentialsAreOnlyInStdinPayload() {
		var command = ModemCommandBuilder.buildAtBatchCommand("/dev/ttyUSB2",
				ModemCommandBuilder.ec200ConfigureAndStartCommands("internet", "test-user", "test-pass"), true);

		assertFalse(command.shellCommand().contains("test-user"));
		assertFalse(command.shellCommand().contains("test-pass"));
		assertFalse(command.shellCommand().contains("dGVzdC1wYXNz"));
		assertTrue(command.shellCommand().contains("exec 3<&0"));
		assertTrue(command.shellCommand().contains("while IFS= read -r command <&3"));
		assertTrue(command.shellCommand().contains("printf '%s\\r' \"$command\" >&4"));
		assertFalse(command.shellCommand().contains("printf '%s\\n' \"$command\" >> \"$out\""));
		assertTrue(command.stdinAsString().contains("AT+QICSGP=1,1,\"internet\",\"test-user\",\"test-pass\",1"));
	}

	private static String decode(String encoded) {
		return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
	}
}
