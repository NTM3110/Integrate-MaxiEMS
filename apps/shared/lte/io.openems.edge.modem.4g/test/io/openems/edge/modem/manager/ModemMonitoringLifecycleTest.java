package io.openems.edge.modem.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModemMonitoringLifecycleTest {

	@Test
	public void testLifecycleTransitionsAvoidDuplicateSchedules() {
		var lifecycle = new ModemMonitoringLifecycle();

		assertFalse(lifecycle.isScheduled());
		assertTrue(lifecycle.applyEnabled(true));
		assertTrue(lifecycle.isScheduled());
		assertFalse(lifecycle.applyEnabled(true));
		assertTrue(lifecycle.applyEnabled(false));
		assertFalse(lifecycle.isScheduled());
		assertFalse(lifecycle.applyEnabled(false));
		assertTrue(lifecycle.applyEnabled(true));
	}
}
