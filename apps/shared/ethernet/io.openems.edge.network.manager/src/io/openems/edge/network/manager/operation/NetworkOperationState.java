package io.openems.edge.network.manager.operation;

public enum NetworkOperationState {
	ACCEPTED(false),
	APPLYING(false),
	VERIFYING(false),
	AWAITING_CONFIRMATION(false),
	CONFIGURED_WAITING_FOR_LINK(true),
	APPLIED(true),
	FAILED(true),
	ROLLING_BACK(false),
	ROLLED_BACK(true),
	CANCELLED(true);

	private final boolean terminal;

	private NetworkOperationState(boolean terminal) {
		this.terminal = terminal;
	}

	public boolean isTerminal() {
		return this.terminal;
	}
}
