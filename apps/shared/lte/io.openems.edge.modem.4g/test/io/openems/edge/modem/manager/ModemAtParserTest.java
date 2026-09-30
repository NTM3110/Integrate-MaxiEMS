package io.openems.edge.modem.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModemAtParserTest {

	@Test
	public void testParsesEc200RuntimeStatus() {
		var output = "ATE1\r\nOK\r\n"
				+ "AT+CSQ\r\n+CSQ: 20,99\r\nOK\r\n"
				+ "AT+CGSN\r\n123456789012345\r\nOK\r\n"
				+ "AT+COPS?\r\n+COPS: 0,0,\"Viettel\",7\r\nOK\r\n"
				+ "AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 1,1,1,1\r\nOK\r\n";

		var status = ModemAtParser.parse(output);

		assertEquals("EC200A", status.model());
		assertEquals("READY", status.simStatus());
		assertTrue(status.isSimReady());
		assertTrue(status.isEc200());
		assertTrue(status.isEcmActive());
		assertEquals(65, status.signalPercent());
		assertEquals(-73, status.signalDbm());
		assertEquals("Viettel", status.operatorName());
		assertEquals("LTE", status.connectionType());
		assertEquals("123456789012345", status.imei());
	}

	@Test
	public void testParsesSimNotInsertedErrorFromCpinResponse() {
		var status = ModemAtParser.parse("AT+CGMM\r\n\r\nEC200A\r\n\r\nOK\r\n"
				+ "AT+CPIN?\r\n\r\n+CME ERROR: 10\r\n"
				+ "AT+QNETDEVCTL?\r\n\r\n+QNETDEVCTL: 0,0,0,0\r\n\r\nOK\r\n");

		assertEquals("NOT INSERTED", status.simStatus());
		assertFalse(status.isSimReady());
	}

	@Test
	public void testDoesNotAttributeOtherCommandErrorToSim() {
		var status = ModemAtParser.parse("AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+CME ERROR: 10\r\n");

		assertEquals("READY", status.simStatus());
		assertTrue(status.isSimReady());
	}

	@Test
	public void testInactiveQnetdevctlIsNotConnected() {
		var status = ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n");

		assertTrue(status.isEc200());
		assertFalse(status.isEcmActive());
	}

	@Test
	public void testParsesEc200ModelWithoutCommandEcho() {
		var status = ModemAtParser.parse("OK\r\n+CSQ: 19,99\r\nOK\r\nEC200A\r\nOK\r\n"
				+ "+CPIN: READY\r\nOK\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n");

		assertEquals("EC200A", status.model());
		assertTrue(status.isEc200());
	}

	@Test
	public void testRecognizesOnlyModemStatusEvidence() {
		assertFalse(ModemAtParser.hasStatusEvidence("OK\r\n"));
		assertFalse(ModemAtParser.hasStatusEvidence("boot OK from unrelated device\r\n"));
		assertFalse(ModemAtParser.hasStatusEvidence("+QNETDEVCTL: 1,1,1,1\r\nOK\r\n"));
		assertTrue(ModemAtParser.hasStatusEvidence("AT+CSQ\r\n+CSQ: 20,99\r\nOK\r\n"));
		assertTrue(ModemAtParser.hasStatusEvidence("AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"));
		assertTrue(ModemAtParser.hasStatusEvidence("AT+CGMM\r\nEC200A\r\nOK\r\n"));
	}

	@Test
	public void testAtQuotedStringRejectsUnsafeCharacters() {
		assertEquals("\"internet\"", ModemAtParser.toAtQuotedString("internet"));
		assertEquals("\"\"", ModemAtParser.toAtQuotedString(null));

		try {
			ModemAtParser.toAtQuotedString("bad\nvalue");
		} catch (IllegalArgumentException e) {
			assertEquals("AT value contains unsupported characters", e.getMessage());
			return;
		}
		throw new AssertionError("Expected control characters to be rejected");
	}
}
