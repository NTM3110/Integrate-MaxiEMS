package io.openems.edge.bms.service.history.implement;

import io.openems.edge.bms.service.history.implement.persistence.MonitoringHistoryStore;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Persists monitoring frames on an isolated worker thread. */
@Designate(ocd = Config.class)
@Component(name = "Bms.Monitoring.History", immediate = true)
public class MonitoringHistoryWriter {

	private static final long PROVIDER_POLL_SECONDS = 5;
	private static final long MAINTENANCE_SECONDS = 300;

	private final Logger log = LoggerFactory.getLogger(MonitoringHistoryWriter.class);
	private final CopyOnWriteArrayList<BmsMonitoringSnapshotProvider> providers = new CopyOnWriteArrayList<>();
	private final Map<String, SourceIdentity> lastPolled = new ConcurrentHashMap<>();

	@Reference
	private BmsDatabase database;

	private volatile MonitoringHistoryPolicy policy = MonitoringHistoryPolicy.defaults();
	private volatile long nextMaintenanceAtMillis;
	private ScheduledExecutorService executor;

	@Reference(service = BmsMonitoringSnapshotProvider.class, cardinality = ReferenceCardinality.MULTIPLE,
			policy = ReferencePolicy.DYNAMIC)
	protected void addMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.providers.addIfAbsent(provider);
	}

	protected void removeMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.providers.remove(provider);
		this.lastPolled.remove(provider.stringKey());
	}

	@Activate
	protected synchronized void activate(Config config) {
		this.apply(config);
	}

	@Modified
	protected synchronized void modified(Config config) {
		try {
			this.apply(config);
		} catch (IllegalArgumentException e) {
			this.log.error("Invalid monitoring history configuration; keeping the active policy", e);
		}
	}

	@Deactivate
	protected synchronized void deactivate() {
		this.stop();
	}

	private void apply(Config config) {
		if (!config.enabled()) {
			if (!this.stop()) {
				this.log.error("Monitoring history writer did not stop after it was disabled");
				return;
			}
			this.lastPolled.clear();
			this.nextMaintenanceAtMillis = 0;
			return;
		}
		var nextPolicy = new MonitoringHistoryPolicy(config.rawCadenceSeconds(), config.rawRetentionDays(),
				config.fiveMinuteRetentionDays(), config.hourlyRetentionDays());
		if (!this.stop()) {
			this.log.error("Monitoring history writer did not stop; refusing to overlap executors");
			return;
		}
		this.policy = nextPolicy;
		this.lastPolled.clear();
		this.nextMaintenanceAtMillis = 0;
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "bms-monitoring-history-writer");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.scheduleWithFixedDelay(this::pollSafely, 0, PROVIDER_POLL_SECONDS, TimeUnit.SECONDS);
	}

	private synchronized boolean stop() {
		if (this.executor != null) {
			this.executor.shutdownNow();
			try {
				if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			this.executor = null;
		}
		return true;
	}

	private void pollSafely() {
		if (!this.database.isAvailable()) return;
		var store = new MonitoringHistoryStore(this.database);
		for (var provider : this.providers) {
			try {
				var frame = provider.latestFrame();
				if (frame == null) continue;
				var identity = new SourceIdentity(frame.snapshotGeneration(), frame.snapshotSequence());
				if (identity.equals(this.lastPolled.get(frame.stringKey()))) continue;
				var result = store.persist(frame, this.policy, Instant.now());
				if (shouldRemember(result)) this.lastPolled.put(frame.stringKey(), identity);
				if (result == MonitoringHistoryStore.PersistResult.STRING_NOT_FOUND) {
					this.log.warn("Skipping monitoring history for unknown BMS string [{}]", frame.stringKey());
				}
			} catch (Exception e) {
				this.log.warn("Unable to persist BMS monitoring history for [{}]", provider.stringKey(), e);
			}
		}
		long nowMillis = System.currentTimeMillis();
		if (nowMillis >= this.nextMaintenanceAtMillis) {
			try {
				store.rollupAndRetain(Instant.ofEpochMilli(nowMillis), this.policy);
				this.nextMaintenanceAtMillis = nowMillis + TimeUnit.SECONDS.toMillis(MAINTENANCE_SECONDS);
			} catch (Exception e) {
				this.log.warn("Unable to aggregate or retain BMS monitoring history", e);
			}
		}
	}

	static boolean shouldRemember(MonitoringHistoryStore.PersistResult result) {
		return result != MonitoringHistoryStore.PersistResult.STRING_NOT_FOUND;
	}

	private record SourceIdentity(UUID generation, long sequence) {
	}
}
