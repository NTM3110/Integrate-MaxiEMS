package io.openems.edge.bms.service.alarms.implement;

import io.openems.edge.bms.service.alarms.implement.persistence.MonitoringAlarmStore;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;

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
import io.openems.edge.bms.service.alarms.implement.persistence.MonitoringAlarmStore.SensorOutputState;

/** Asynchronous alarm consumer; never runs JDBC on the OpenEMS scheduler. */
@Designate(ocd = AlarmConfig.class)
@Component(name = "Bms.Monitoring.Alarms", immediate = true, service = MonitoringAlarmService.class)
public class MonitoringAlarmProcessor implements MonitoringAlarmService {

	private final Logger log = LoggerFactory.getLogger(MonitoringAlarmProcessor.class);
	private final CopyOnWriteArrayList<BmsMonitoringSnapshotProvider> providers = new CopyOnWriteArrayList<>();
	private final Map<String, SourceIdentity> lastPolled = new ConcurrentHashMap<>();
	private final Map<String, OutputSync> lastOutputSync = new ConcurrentHashMap<>();
	private final Map<String, OutputSync> lastOutputFailure = new ConcurrentHashMap<>();
	private long lastUnconfiguredCheckNanos;
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
		this.lastOutputSync.remove(provider.stringKey());
		this.lastOutputFailure.remove(provider.stringKey());
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
			this.lastOutputSync.clear();
			this.lastOutputFailure.clear();
			this.lastUnconfiguredCheckNanos = 0;
			return;
		}
		if (config.pollSeconds() < 1) throw new IllegalArgumentException("Alarm poll interval must be positive");
		if (!this.stop()) {
			this.log.error("Monitoring alarm processor did not stop; refusing to overlap executors");
			return;
		}
		this.lastPolled.clear();
		this.lastOutputSync.clear();
		this.lastOutputFailure.clear();
		this.lastUnconfiguredCheckNanos = 0;
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
				if (!frame.stringKey().equals(provider.stringKey())) continue;
				var identity = new SourceIdentity(frame.publicationGeneration(), frame.publicationRevision());
				var result = identity.equals(this.lastPolled.get(frame.stringKey()))
						? MonitoringAlarmStore.ProcessResult.DUPLICATE
						: store.process(frame, this.policy, Instant.now());
				if (result != MonitoringAlarmStore.ProcessResult.STRING_NOT_FOUND) {
					this.lastPolled.put(frame.stringKey(), identity);
					if (result == MonitoringAlarmStore.ProcessResult.PROCESSED
							|| result == MonitoringAlarmStore.ProcessResult.DUPLICATE) {
						this.syncSensorOutput(store, frame.stringKey());
					}
				} else {
					this.log.warn("Skipping monitoring alarms for unknown BMS string [{}]", frame.stringKey());
				}
			} catch (Exception e) {
				this.log.warn("Unable to process BMS monitoring alarms for [{}]", provider.stringKey(), e);
			}
		}
		// Outputs are fixed to string IDs, not to provider order. A string that
		// does not exist has no alarm source and its pin belongs at normal level.
		// A configured string without a processable frame keeps its current level.
		long now = System.nanoTime();
		if (this.lastUnconfiguredCheckNanos == 0
				|| now - this.lastUnconfiguredCheckNanos >= TimeUnit.SECONDS.toNanos(10)) {
			this.lastUnconfiguredCheckNanos = now;
			for (var key : List.of("str1", "str2")) {
				try {
					if (store.sensorOutputState(key) == SensorOutputState.STRING_NOT_FOUND) {
						this.syncSensorOutput(store, key);
					}
				} catch (Exception e) {
					this.log.warn("Unable to reconcile unconfigured GPIO alarm output for [{}]", key, e);
				}
			}
		}
	}

	private void syncSensorOutput(MonitoringAlarmStore store, String stringKey) throws Exception {
		int index = switch (stringKey) {
			case "str1" -> 1;
			case "str2" -> 2;
			default -> 0;
		};
		if (index == 0) return;
		var state = store.sensorOutputState(stringKey);
		if (state != SensorOutputState.ACTIVE && state != SensorOutputState.INACTIVE
				&& state != SensorOutputState.STRING_NOT_FOUND) return;
		var last = this.lastOutputSync.get(stringKey);
		long now = System.nanoTime();
		// A mode switch clears the helper's runtime marker. A bounded readback
		// retry restores it without invoking sudo on every duplicate frame poll.
		if (last != null && last.state() == state && now - last.checkedAtNanos() < TimeUnit.SECONDS.toNanos(10)) {
			return;
		}
		var failed = this.lastOutputFailure.get(stringKey);
		if (failed != null && failed.state() == state
				&& now - failed.checkedAtNanos() < TimeUnit.SECONDS.toNanos(10)) return;
		this.lastOutputFailure.put(stringKey, new OutputSync(state, now));
		var process = new ProcessBuilder("sudo", "-n", "/usr/local/sbin/maxiems-gpio", "drive").start();
		try (var input = process.getOutputStream()) {
			input.write(("{\"stringIndex\":" + index + ",\"alarm\":"
					+ (state == SensorOutputState.ACTIVE) + "}").getBytes(StandardCharsets.UTF_8));
		}
		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("GPIO alarm output helper timed out for " + stringKey);
		}
		var response = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		var json = JsonParser.parseString(response).getAsJsonObject();
		var output = json.getAsJsonObject("strings").getAsJsonObject(Integer.toString(index));
		if (process.exitValue() != 0 || !json.get("confirmed").getAsBoolean()
				|| !output.get("synchronized").getAsBoolean()
				|| output.get("alarm").getAsBoolean() != (state == SensorOutputState.ACTIVE)) {
			throw new IllegalStateException("GPIO alarm output was not confirmed for " + stringKey + ": "
					+ (json.has("errorCode") ? json.get("errorCode") : "READBACK_MISMATCH"));
		}
		this.lastOutputSync.put(stringKey, new OutputSync(state, now));
		this.lastOutputFailure.remove(stringKey);
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

	private record OutputSync(SensorOutputState state, long checkedAtNanos) {
	}
}
