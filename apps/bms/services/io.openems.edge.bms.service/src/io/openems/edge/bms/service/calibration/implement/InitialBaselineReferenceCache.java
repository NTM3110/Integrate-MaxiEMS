package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.InitialBaselineReferenceDatasetService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;
import io.openems.edge.bms.service.reference.contracts.InitialBaselineReferenceDatasetProvider;
import io.openems.edge.bms.service.reference.contracts.InitialBaselineReferenceDatasetProvider.Snapshot;

/** Refreshes approved references off-cycle and exposes lock-free snapshots. */
@Component(name = "io.openems.edge.bms.platform.calibration.InitialBaselineReferenceCache", service = InitialBaselineReferenceDatasetProvider.class, immediate = true)
public class InitialBaselineReferenceCache implements InitialBaselineReferenceDatasetProvider {

	private final Logger log = LoggerFactory.getLogger(InitialBaselineReferenceCache.class);

	@Reference
	private InitialBaselineReferenceDatasetService service;
	private volatile Snapshot snapshot = Snapshot.empty();
	private ScheduledExecutorService executor;

	@Activate
	protected void activate() {
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "bms-initial-baseline-reference-cache");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.scheduleWithFixedDelay(this::refreshSafely, 0, 30, TimeUnit.SECONDS);
	}

	@Deactivate
	protected void deactivate() {
		if (this.executor != null) this.executor.shutdownNow();
	}

	@Override
	public Snapshot snapshot() {
		return this.snapshot;
	}

	void refresh() {
		var attemptedAt = Instant.now();
		var values = this.service.loadAllActive();
		var idValues = new java.util.LinkedHashMap<UUID, InitialBaselineReferenceDataset>();
		var keyValues = new java.util.LinkedHashMap<String, InitialBaselineReferenceDataset>();
		for (var value : values) {
			if (idValues.putIfAbsent(value.stringId(), value) != null) {
				throw new IllegalStateException("Duplicate active Initial Baseline string id " + value.stringId());
			}
			if (keyValues.putIfAbsent(value.stringKey(), value) != null) {
				throw new IllegalStateException("Duplicate active Initial Baseline string key " + value.stringKey());
			}
		}
		this.snapshot = new Snapshot(idValues, keyValues, attemptedAt, Instant.now(), null);
	}

	void refreshSafely() {
		try {
			this.refresh();
		} catch (RuntimeException e) {
			var previous = this.snapshot;
			this.snapshot = new Snapshot(previous.byStringId(), previous.byStringKey(), Instant.now(),
					previous.lastSuccessfulRefreshAt(), e.getClass().getSimpleName() + ": " + e.getMessage());
			this.log.warn("Unable to refresh Initial Baseline reference cache; retaining the last valid dataset", e);
		}
	}
}
