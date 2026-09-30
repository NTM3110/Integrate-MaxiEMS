package io.openems.edge.bms.service.strings.contracts;

import java.util.List;

import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;

/**
 * Coordinates the authoritative string component lifecycle with required
 * business persistence without exposing concrete lifecycle or repository
 * implementations to callers.
 *
 * <p>The runtime lifecycle remains the only owner of TA, TC, Calculator and
 * Monitoring configurations.</p>
 */
public interface BmsStringManager {

	Definition create(Definition request) throws Exception;

	Definition update(Definition request) throws Exception;

	List<Definition> list() throws Exception;

	Definition get(int stringIndex) throws Exception;

	void reconcile() throws Exception;

	void delete(int stringIndex) throws Exception;
}
