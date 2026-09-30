package io.openems.edge.modem.manager;

final class ModemMonitoringLifecycle {

	private boolean scheduled;

	boolean applyEnabled(boolean enabled) {
		if (enabled == this.scheduled) {
			return false;
		}
		this.scheduled = enabled;
		return true;
	}

	boolean isScheduled() {
		return this.scheduled;
	}

	void markStopped() {
		this.scheduled = false;
	}
}
