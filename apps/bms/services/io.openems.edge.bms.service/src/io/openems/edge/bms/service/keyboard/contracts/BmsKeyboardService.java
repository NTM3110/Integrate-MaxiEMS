package io.openems.edge.bms.service.keyboard.contracts;

import io.openems.common.exceptions.OpenemsException;

/** Constrained control of the Sway-owned kiosk virtual keyboard. */
public interface BmsKeyboardService {

	void command(Action action) throws OpenemsException;

	enum Action {
		NORMAL("normal"), DIALER("dialer"), HIDE("hide");

		private final String wireName;

		Action(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return this.wireName;
		}

		public static Action parse(String value) {
			for (var action : values()) if (action.wireName.equals(value)) return action;
			throw new IllegalArgumentException("Keyboard action must be normal, dialer, or hide");
		}
	}
}
