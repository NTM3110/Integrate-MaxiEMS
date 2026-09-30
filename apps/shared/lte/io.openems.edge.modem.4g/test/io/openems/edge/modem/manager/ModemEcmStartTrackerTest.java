package io.openems.edge.modem.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModemEcmStartTrackerTest {

	@Test
	public void testRearmsAfterImmediateInactiveRequery() {
		var tracker = new ModemEcmStartTracker();

		assertTrue(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n")));
		tracker.markStartAttempted();
		tracker.afterImmediateRequery(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n"));

		assertFalse(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n")));
		assertFalse(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n")));
		assertTrue(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n")));
	}

	@Test
	public void testDoesNotRestartWhileActive() {
		var tracker = new ModemEcmStartTracker();

		assertFalse(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 1,1,1,1\r\nOK\r\n")));
	}

	@Test
	public void testDoesNotStartWithoutReadySim() {
		var tracker = new ModemEcmStartTracker();

		assertFalse(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CME ERROR: 10\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n")));
		assertFalse(tracker.shouldStart(ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: SIM PIN\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n")));
	}

	@Test
	public void testBacksOffAfterImmediateInactiveRequery() {
		var tracker = new ModemEcmStartTracker(2);
		var inactive = ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n");

		assertTrue(tracker.shouldStart(inactive));
		tracker.markStartAttempted();
		tracker.afterImmediateRequery(inactive);
		assertFalse(tracker.shouldStart(inactive));
		assertFalse(tracker.shouldStart(inactive));
		assertTrue(tracker.shouldStart(inactive));
	}

	@Test
	public void testActiveClearsBackoff() {
		var tracker = new ModemEcmStartTracker(2);
		var inactive = ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 0,0,0,0\r\nOK\r\n");
		var active = ModemAtParser.parse("AT+CGMM\r\nEC200A\r\nOK\r\n"
				+ "AT+CPIN?\r\n+CPIN: READY\r\nOK\r\n"
				+ "AT+QNETDEVCTL?\r\n+QNETDEVCTL: 1,1,1,1\r\nOK\r\n");

		assertTrue(tracker.shouldStart(inactive));
		tracker.markStartAttempted();
		tracker.afterImmediateRequery(inactive);
		assertFalse(tracker.shouldStart(active));
		assertTrue(tracker.shouldStart(inactive));
	}
}
