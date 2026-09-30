package io.openems.edge.core.host;

import java.util.List;
import java.util.Map;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;

/** Applies generated systemd-networkd files through the constrained helper. */
@FunctionalInterface
public interface NetworkApplyCollaborator {

	/**
	 * Stages, validates, applies and confirms a network transaction.
	 *
	 * @param configurations interface name to generated file lines
	 * @throws OpenemsNamedException on validation or helper failure
	 */
	void apply(Map<String, List<String>> configurations) throws OpenemsNamedException;
}
