package io.openems.edge.bms.service.alarms.implement;

import io.openems.edge.bms.service.alarms.implement.persistence.MonitoringAlarmStore;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
import io.openems.edge.bms.service.monitoring.contracts.MonitoringAlarmService;
import io.openems.edge.bms.service.persistence.implement.BmsDatabase;

/** Asynchronous alarm consumer; never runs JDBC on the OpenEMS scheduler. */
@Designate(ocd = AlarmConfig.class)
@Component(name = "Bms.Monitoring.Alarms", immediate = true, service = MonitoringAlarmService.class)
public class MonitoringAlarmProcessor implements MonitoringAlarmService {

	private final Logger log = LoggerFactory.getLogger(MonitoringAlarmProcessor.class);
	private final CopyOnWriteArrayList<BmsMonitoringSnapshotProvider> providers = new CopyOnWriteArrayList<>();
	private final Map<String, SourceIdentity> lastPolled = new ConcurrentHashMap<>();
	private final AlarmLifecyclePolicy policy = AlarmLifecyclePolicy.defaults();

	@Reference
	private BmsDatabase database;

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
	protected synchronized void activate(AlarmConfig config) {
		this.apply(config);
	}

	@Modified
	protected synchronized void modified(AlarmConfig config) {
		try {
			this.apply(config);
		} catch (IllegalArgumentException e) {
			this.log.error("Invalid monitoring alarm configuration; keeping the active processor", e);
		}
	}

	@Deactivate
	protected synchronized void deactivate() {
		this.stop();
	}

	@Override
	public List<AlarmOccurrence> list(String stringKey, boolean includeCleared, int offset, int limit)
			throws SQLException {
		return new MonitoringAlarmStore(this.database).list(stringKey, includeCleared, offset, limit);
	}

	@Override
	public AlarmListResult listWithCheckpoint(String stringKey, boolean includeCleared, int offset, int limit)
			throws SQLException {
		return new MonitoringAlarmStore(this.database).listWithCheckpoint(stringKey, includeCleared, offset, limit);
	}

	@Override
	public AcknowledgeResult acknowledge(long occurrenceId, String actor,
			Instant acknowledgedAt) throws SQLException {
		return new MonitoringAlarmStore(this.database).acknowledge(occurrenceId, actor, acknowledgedAt);
	}

	private void apply(AlarmConfig config) {
		if (!config.enabled()) {
			if (!this.stop()) this.log.error("Monitoring alarm processor did not stop after it was disabled");
			this.lastPolled.clear();
			return;
		}
		if (config.pollSeconds() < 1) throw new IllegalArgumentException("Alarm poll interval must be positive");
		if (!this.stop()) {
			this.log.error("Monitoring alarm processor did not stop; refusing to overlap executors");
			return;
		}
		this.lastPolled.clear();
		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "bms-monitoring-alarm-processor");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.scheduleWithFixedDelay(this::pollSafely, 0, config.pollSeconds(), TimeUnit.SECONDS);
	}

	private void pollSafely() {
		if (!this.database.isAvailable()) return;
		var store = new MonitoringAlarmStore(this.database);
		for (var provider : this.providers) {
			try {
				var frame = provider.latestFrame();
				if (frame == null) continue;
				var identity = new SourceIdentity(frame.publicationGeneration(), frame.publicationRevision());
				if (identity.equals(this.lastPolled.get(frame.stringKey()))) continue;
				var result = store.process(frame, this.policy, Instant.now());
				if (result != MonitoringAlarmStore.ProcessResult.STRING_NOT_FOUND) {
					this.lastPolled.put(frame.stringKey(), identity);
				} else {
					this.log.warn("Skipping monitoring alarms for unknown BMS string [{}]", frame.stringKey());
				}
			} catch (Exception e) {
				this.log.warn("Unable to process BMS monitoring alarms for [{}]", provider.stringKey(), e);
			}
		}
	}

	private synchronized boolean stop() {
		if (this.executor == null) return true;
		this.executor.shutdownNow();
		try {
			if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
				this.log.error("Monitoring alarm processor did not stop within 10 seconds");
				return false;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		this.executor = null;
		return true;
	}

	private record SourceIdentity(UUID generation, long sequence) {
	}
}
