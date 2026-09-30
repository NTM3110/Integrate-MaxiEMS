package io.openems.edge.modem.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ModemCommandBuilderTest {

	@Test
	public void testStatusCommandsDisableEchoBeforeQueries() {
		assertEquals("ATE0", ModemCommandBuilder.statusCommands().get(0));
	}

	@Test
	public void testBuildsSequentialStatusTransactions() {
		var atCommands = List.of("ATE0", "AT+CSQ", "AT+CGMM");
		var command = ModemCommandBuilder.buildAtBatchCommand("/dev/ttyUSB2", atCommands, false);

		assertEquals("ATE0\nAT+CSQ\nAT+CGMM\n", command.stdinAsString());
		assertTrue(command.shellCommand().contains("while IFS= read -r command <&3"));
		assertTrue(command.shellCommand().contains("stty -F /dev/ttyUSB2 raw -echo"));
		assertFalse(command.shellCommand().contains("115200"));
		assertTrue(command.shellCommand().contains("printf '%s\\r' \"$command\" >&4"));
		assertTrue(command.shellCommand().contains("while (( SECONDS < endSecond ))"));
		assertTrue(command.shellCommand().contains("ERROR|'+CME ERROR:'*|'+CMS ERROR:'*"));
		assertFalse(command.shellCommand().contains("cat <&3 > /dev/ttyUSB2"));
		assertFalse(command.shellCommand().contains("exit 9"));
	}

	@Test
	public void testConfigTransactionStopsOnTimeoutOrError() {
		var command = ModemCommandBuilder.buildAtBatchCommand("/dev/ttyUSB2",
				ModemCommandBuilder.ec200ConfigureAndStartCommands("internet", "", ""), true);

		assertTrue(command.shellCommand().contains("exit 8"));
		assertTrue(command.shellCommand().contains("exit 9"));
	}

	@Test
	public void testEc200QicsgpUsesNoAuthWithoutCredentials() {
		assertEquals("AT+QICSGP=1,1,\"internet\",\"\",\"\",0",
				ModemCommandBuilder.ec200ConfigureAndStartCommands("internet", "", "").get(1));
	}

	@Test
	public void testEc200QicsgpUsesAuthWithCredentials() {
		assertEquals("AT+QICSGP=1,1,\"internet\",\"test-user\",\"test-pass\",1",
				ModemCommandBuilder.ec200ConfigureAndStartCommands("internet", "test-user", "test-pass").get(1));
	}

	@Test
	public void testBuildsEc200ConfigBatchWithoutPlainCredentialsInShell() {
		var command = ModemCommandBuilder.buildAtBatchCommand("/dev/ttyUSB2",
				ModemCommandBuilder.ec200ConfigureAndStartCommands("internet", "user", "test-value"), true);

		assertTrue(command.shellCommand().contains("/dev/ttyUSB2"));
		assertFalse(command.shellCommand().contains("test-value"));
		assertFalse(command.shellCommand().contains("AT+QICSGP"));
		assertTrue(command.stdinAsString().contains("AT+QICSGP"));
	}

	@Test
	public void testPppScriptUpdateDoesNotExposePasswordInReturnedCommand() {
		var command = ModemCommandBuilder.buildPppConfigCommand("internet", "user", "test-value");

		assertFalse(command.shellCommand().contains("test-value"));
		assertFalse(command.shellCommand().contains("QL_PASSWORD=test-value"));
		assertFalse(command.stdinAsString().contains("test-value"));
		assertEquals(3, command.stdinAsString().lines().count());
	}
}
