package io.openems.edge.modem.manager;

import io.openems.edge.modem.manager.ModemAtParser.AtStatus;

final class ModemEcmStartTracker {

	private boolean startAttempted;
	private boolean lastActive;
	private final int inactiveBackoffCycles;
	private int remainingBackoffCycles;

	ModemEcmStartTracker() {
		this(2);
	}

	ModemEcmStartTracker(int inactiveBackoffCycles) {
		this.inactiveBackoffCycles = inactiveBackoffCycles;
	}

	boolean shouldStart(AtStatus status) {
		if (!status.isEc200()) {
			return false;
		}
		if (!status.isSimReady()) {
			return false;
		}
		if (status.isEcmActive()) {
			this.startAttempted = false;
			this.lastActive = true;
			this.remainingBackoffCycles = 0;
			return false;
		}
		if (this.lastActive) {
			this.startAttempted = false;
			this.lastActive = false;
		}
		if (this.remainingBackoffCycles > 0) {
			this.remainingBackoffCycles--;
			return false;
		}
		return !this.startAttempted;
	}

	void markStartAttempted() {
		this.startAttempted = true;
	}

	void afterImmediateRequery(AtStatus status) {
		if (status.isEc200() && !status.isEcmActive()) {
			this.startAttempted = false;
			this.lastActive = false;
			this.remainingBackoffCycles = this.inactiveBackoffCycles;
		}
	}

	void reset() {
		this.startAttempted = false;
		this.lastActive = false;
		this.remainingBackoffCycles = 0;
	}
}
