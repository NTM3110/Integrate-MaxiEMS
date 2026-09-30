package io.openems.edge.inverter.hypontech;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.google.gson.JsonElement;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy.Snapshot;
import io.openems.edge.timedata.api.Timedata;

final class HypontechDailyProductionEnergyRestorer {
	private enum State {
		NOT_STARTED,
		QUERYING,
		SNAPSHOT_PENDING,
		RESTORED,
		RETRY_WAIT
	}

	static final Duration RETRY_DELAY = Duration.ofMinutes(1);

	private final Clock clock;
	private final Executor executor;
	private final Supplier<Timedata> timedataSupplier;
	private final Object timedataAccessLock;
	private boolean active;
	private long generation;
	private LocalDate date;
	private State state = State.NOT_STARTED;
	private Instant retryAt = Instant.MIN;
	private Snapshot pendingSnapshot;

	HypontechDailyProductionEnergyRestorer(Clock clock, Executor executor, Supplier<Timedata> timedataSupplier) {
		this(clock, executor, timedataSupplier, new Object());
	}

	HypontechDailyProductionEnergyRestorer(Clock clock, Executor executor, Supplier<Timedata> timedataSupplier,
			Object timedataAccessLock) {
		this.clock = Objects.requireNonNull(clock);
		this.executor = Objects.requireNonNull(executor);
		this.timedataSupplier = Objects.requireNonNull(timedataSupplier);
		this.timedataAccessLock = Objects.requireNonNull(timedataAccessLock);
	}

	synchronized void activate() {
		this.active = true;
		this.generation++;
		this.reset(LocalDate.now(this.clock));
	}

	synchronized void deactivate() {
		this.active = false;
		this.generation++;
		this.pendingSnapshot = null;
	}

	synchronized void onTimedataAvailable() {
		this.onTimedataChanged();
	}

	synchronized void onTimedataChanged() {
		if (!this.active) {
			return;
		}
		this.generation++;
		this.state = State.NOT_STARTED;
		this.retryAt = Instant.MIN;
		this.pendingSnapshot = null;
	}

	synchronized Optional<Snapshot> pollSnapshot(LocalDate currentDate) {
		this.resetIfDateChanged(currentDate);
		if (!this.active || this.state != State.SNAPSHOT_PENDING) {
			return Optional.empty();
		}
		var result = this.pendingSnapshot;
		this.pendingSnapshot = null;
		this.state = State.RESTORED;
		return Optional.of(result);
	}

	void request(String componentId, LocalDate currentDate) {
		final Timedata timedata;
		final long requestGeneration;
		final ZonedDateTime now;
		synchronized (this) {
			this.resetIfDateChanged(currentDate);
			if (!this.active || this.state == State.QUERYING || this.state == State.SNAPSHOT_PENDING
					|| this.state == State.RESTORED || this.clock.instant().isBefore(this.retryAt)) {
				return;
			}
			try {
				timedata = this.timedataSupplier.get();
			} catch (RuntimeException e) {
				this.scheduleRetry();
				return;
			}
			if (timedata == null) {
				this.scheduleRetry();
				return;
			}
			this.state = State.QUERYING;
			requestGeneration = this.generation;
			now = ZonedDateTime.now(this.clock);
		}

		try {
			this.executor.execute(() -> this.runQuery(timedata, componentId, currentDate, requestGeneration, now));
		} catch (RuntimeException e) {
			this.complete(requestGeneration, currentDate, Optional.empty());
		}
	}

	private void runQuery(Timedata timedata, String componentId, LocalDate requestDate, long requestGeneration,
			ZonedDateTime now) {
		final Optional<Snapshot> snapshot;
		try {
			synchronized (this.timedataAccessLock) {
				if (this.timedataSupplier.get() != timedata) {
					return;
				}
				snapshot = querySnapshot(timedata, componentId, now);
			}
		} catch (Exception e) {
			this.complete(requestGeneration, requestDate, Optional.empty());
			return;
		}
		this.complete(requestGeneration, requestDate, snapshot);
	}

	private synchronized void complete(long requestGeneration, LocalDate requestDate, Optional<Snapshot> snapshot) {
		if (!this.active || this.generation != requestGeneration || !Objects.equals(this.date, requestDate)
				|| this.state != State.QUERYING) {
			return;
		}
		if (snapshot.isPresent()) {
			this.pendingSnapshot = snapshot.get();
			this.state = State.SNAPSHOT_PENDING;
		} else {
			this.scheduleRetry();
		}
	}

	private void resetIfDateChanged(LocalDate currentDate) {
		if (!Objects.equals(this.date, currentDate)) {
			this.generation++;
			this.reset(currentDate);
		}
	}

	private void reset(LocalDate currentDate) {
		this.date = currentDate;
		this.state = State.NOT_STARTED;
		this.retryAt = Instant.MIN;
		this.pendingSnapshot = null;
	}

	private void scheduleRetry() {
		this.pendingSnapshot = null;
		this.state = State.RETRY_WAIT;
		this.retryAt = this.clock.instant().plus(RETRY_DELAY);
	}

	static Optional<Snapshot> querySnapshot(Timedata timedata, String componentId, ZonedDateTime now)
			throws OpenemsNamedException {
		var date = now.toLocalDate();
		var startOfDay = date.atStartOfDay(now.getZone());
		var totalAddress = new ChannelAddress(componentId,
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY.id());
		var todayAddress = new ChannelAddress(componentId,
				ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY.id());
		var data = timedata.queryHistoricData(null, startOfDay, now, Set.of(totalAddress, todayAddress),
				new Resolution(1, ChronoUnit.MINUTES));
		if (data == null || data.isEmpty()) {
			return Optional.empty();
		}
		var sortedData = new TreeMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>>();
		sortedData.putAll(data);

		for (var entry : sortedData.descendingMap().entrySet()) {
			var row = entry.getValue();
			if (row == null) {
				continue;
			}
			var total = nonNegativeLong(row.get(totalAddress));
			var today = nonNegativeLong(row.get(todayAddress));
			if (total != null && today != null && today <= total) {
				return Optional.of(snapshot(date, total - today, total, entry.getKey()));
			}
		}

		return Optional.empty();
	}

	private static Snapshot snapshot(LocalDate date, long baselineWh, long lastTotalWh, ZonedDateTime timestamp) {
		return new Snapshot(date, null, baselineWh, true, lastTotalWh, timestamp.toInstant(), timestamp.toInstant(),
				null, 0L, 0L, false);
	}

	private static Long nonNegativeLong(JsonElement value) {
		try {
			var result = JsonUtils.getAsOptionalLong(value).orElse(null);
			return result != null && result >= 0 ? result : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

}
