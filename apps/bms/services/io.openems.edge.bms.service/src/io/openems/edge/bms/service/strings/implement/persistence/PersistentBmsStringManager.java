package io.openems.edge.bms.service.strings.implement.persistence;

import io.openems.edge.bms.service.strings.contracts.StringRepository;

import java.util.List;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.service.strings.implement.lifecycle.BmsStringLifecycle;
import io.openems.edge.bms.service.strings.contracts.BmsStringManager;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;

/** Keeps PostgreSQL metadata synchronized with the core-owned OSGi lifecycle. */
@Component(name = "io.openems.edge.bms.platform.strings.PersistentBmsStringManager", service = BmsStringManager.class)
public class PersistentBmsStringManager implements BmsStringManager {
	private final Logger log = LoggerFactory.getLogger(PersistentBmsStringManager.class);

	@Reference
	private BmsStringLifecycle lifecycle;
	@Reference
	private StringRepository repository;

	@Activate
	protected void activate() {
		try {
			this.reconcile();
		} catch (Exception e) {
			// Runtime components must remain available when optional PostgreSQL is down.
			// The next string mutation or calibration create retries synchronization.
			this.log.warn("Unable to fully reconcile BMS string runtime components and metadata during activation", e);
		}
	}

	@Override
	public synchronized Definition create(Definition request) throws Exception {
		var created = this.lifecycle.create(request);
		try {
			this.repository.synchronize(created);
			return created;
		} catch (Exception persistenceError) {
			try {
				this.lifecycle.delete(created.stringIndex());
			} catch (Exception rollbackError) {
				persistenceError.addSuppressed(rollbackError);
			}
			throw persistenceError;
		}
	}

	@Override
	public synchronized Definition update(Definition request) throws Exception {
		var previous = this.lifecycle.get(request.stringIndex());
		var updated = this.lifecycle.update(request);
		try {
			this.repository.synchronize(updated);
			return updated;
		} catch (Exception persistenceError) {
			try {
				this.lifecycle.update(previous);
			} catch (Exception rollbackError) {
				persistenceError.addSuppressed(rollbackError);
			}
			throw persistenceError;
		}
	}

	@Override
	public synchronized List<Definition> list() throws Exception {
		return this.lifecycle.list();
	}

	@Override
	public synchronized Definition get(int stringIndex) throws Exception {
		return this.lifecycle.get(stringIndex);
	}

	@Override
	public synchronized void reconcile() throws Exception {
		var definitions = this.lifecycle.list();
		Exception failure = null;

		// Repair every runtime first. A PostgreSQL failure must not prevent a later
		// string from getting its Calculator and Monitoring components repaired.
		for (var definition : definitions) {
			try {
				this.lifecycle.reconcileCalculator(definition);
			} catch (Exception e) {
				failure = addReconciliationFailure(failure, "runtime components", definition, e);
			}
		}

		// Metadata synchronization is a separate best-effort pass. Continue after a
		// failed row so activation reports every observed inconsistency truthfully.
		for (var definition : definitions) {
			try {
				this.repository.synchronize(definition);
			} catch (Exception e) {
				failure = addReconciliationFailure(failure, "PostgreSQL metadata", definition, e);
			}
		}

		if (failure != null) {
			throw failure;
		}
	}

	@Override
	public synchronized void delete(int stringIndex) throws Exception {
		var previous = this.lifecycle.get(stringIndex);
		// Reconcile first so legacy rows with NULL required fields can still be
		// deleted without map() silently manufacturing zero values.
		var persisted = this.repository.synchronize(previous);
		this.lifecycle.delete(stringIndex);
		try {
			this.repository.delete(persisted);
		} catch (Exception persistenceError) {
			try {
				this.lifecycle.create(previous);
			} catch (Exception rollbackError) {
				persistenceError.addSuppressed(rollbackError);
			}
			throw persistenceError;
		}
	}

	private static Exception addReconciliationFailure(Exception aggregate, String target, Definition definition,
			Exception cause) {
		var contextual = new Exception(
				"Failed to reconcile " + target + " for BMS string " + definition.stringIndex(), cause);
		if (aggregate == null) {
			return contextual;
		}
		aggregate.addSuppressed(contextual);
		return aggregate;
	}
}
