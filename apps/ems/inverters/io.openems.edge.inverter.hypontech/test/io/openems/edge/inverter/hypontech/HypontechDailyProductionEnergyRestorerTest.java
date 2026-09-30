package io.openems.edge.inverter.hypontech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import io.openems.common.exceptions.NotImplementedException;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.timedata.Resolution;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy;
import io.openems.edge.pvinverter.api.CalculateDailyProductionEnergy.Source;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.Timeranges;

public class HypontechDailyProductionEnergyRestorerTest {

	private static final String COMPONENT_ID = "inverter0";
	private static final ChannelAddress TOTAL = new ChannelAddress(COMPONENT_ID,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY.id());
	private static final ChannelAddress TODAY = new ChannelAddress(COMPONENT_ID,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_TODAY.id());

	@Test
	public void testUsesLatestValidTotalTodayPair() throws Exception {
		var now = ZonedDateTime.parse("2026-08-04T12:00:00Z");
		var timedata = new RecordingTimedata("timedata0");
		addPair(timedata, now.minusHours(2), 120_000L, 4_000L);
		addPair(timedata, now.minusHours(1), 125_000L, 5_000L);
		addPair(timedata, now.minusMinutes(30), 126_000L, 127_000L);

		var snapshot = HypontechDailyProductionEnergyRestorer.querySnapshot(timedata, COMPONENT_ID, now)
				.orElseThrow();
		var calculator = new CalculateDailyProductionEnergy(ZoneOffset.UTC, Duration.ofMinutes(5), snapshot);
		var result = calculator.update(now.toInstant(), null, 125_500L, 1_000);

		assertEquals(Long.valueOf(5_500L), result.energyWh());
		assertEquals(Source.TOTAL_COUNTER, result.source());
		assertEquals(now.toLocalDate(), snapshot.date());
		assertEquals(null, snapshot.lastDeviceTodayEnergyWh());
		assertEquals(Long.valueOf(120_000L), snapshot.totalBaselineWh());
		assertTrue(snapshot.totalBaselineTrusted());
		assertEquals(Long.valueOf(125_000L), snapshot.lastTotalEnergyWh());
		assertEquals(now.minusHours(1).toInstant(), snapshot.lastTotalTimestamp());
		assertEquals(now.minusHours(1).toInstant(), snapshot.lastTimestamp());
		assertEquals(null, snapshot.lastActivePowerW());
		assertEquals(0L, snapshot.powerEnergyWh());
		assertEquals(0L, snapshot.continuousWattMillis());
		assertFalse(snapshot.powerEnergyTrusted());
		assertEquals(now.toLocalDate().atStartOfDay(ZoneOffset.UTC), timedata.fromDate);
		assertEquals(now, timedata.toDate);
		assertEquals(Set.of(TOTAL, TODAY), timedata.channels);
		assertEquals(1L, timedata.resolution.getValue());
		assertEquals(ChronoUnit.MINUTES, timedata.resolution.getUnit());
	}

	@Test
	public void testRejectsExpandedHourlyTotalOnlyRowsNearMidnight() throws Exception {
		var now = ZonedDateTime.parse("2026-08-04T12:00:00Z");
		var startOfDay = now.toLocalDate().atStartOfDay(ZoneOffset.UTC);
		var timedata = new TestTimedata("timedata0");
		for (var minute = 0; minute <= 5; minute++) {
			timedata.add(startOfDay.plusMinutes(minute), TOTAL, new JsonPrimitive(100_000L));
		}

		assertTrue(HypontechDailyProductionEnergyRestorer.querySnapshot(timedata, COMPONENT_ID, now).isEmpty());
	}

	@Test
	public void testReturnsEmptyForEmptyData() throws Exception {
		var now = ZonedDateTime.parse("2026-08-04T12:00:00Z");

		assertTrue(HypontechDailyProductionEnergyRestorer
				.querySnapshot(new TestTimedata("timedata0"), COMPONENT_ID, now).isEmpty());
	}

	@Test
	public void testRejectsTodayGreaterThanTotal() throws Exception {
		var now = ZonedDateTime.parse("2026-08-04T12:00:00Z");
		var timedata = new TestTimedata("timedata0");
		addPair(timedata, now.minusHours(1), 4_000L, 5_000L);

		assertTrue(HypontechDailyProductionEnergyRestorer.querySnapshot(timedata, COMPONENT_ID, now).isEmpty());
	}

	@Test
	public void testReverseSortedDataStillUsesLatestValidPair() throws Exception {
		var now = ZonedDateTime.parse("2026-08-04T12:00:00Z");
		var timedata = new ReverseTimedata("timedata0");
		addPair(timedata, now.minusHours(2), 120_000L, 4_000L);
		addPair(timedata, now.minusHours(1), 125_000L, 5_000L);

		var snapshot = HypontechDailyProductionEnergyRestorer.querySnapshot(timedata, COMPONENT_ID, now)
				.orElseThrow();

		assertEquals(Long.valueOf(120_000L), snapshot.totalBaselineWh());
		assertEquals(Long.valueOf(125_000L), snapshot.lastTotalEnergyWh());
		assertEquals(now.minusHours(1).toInstant(), snapshot.lastTimestamp());
	}

	@Test
	public void testSkipsNullRowAndUsesValidCandidate() throws Exception {
		var now = ZonedDateTime.parse("2026-08-04T12:00:00Z");
		var validTimestamp = now.minusHours(1);
		var timedata = new NullRowTimedata("timedata0", now.minusMinutes(30));
		addPair(timedata, validTimestamp, 125_000L, 5_000L);

		var snapshot = HypontechDailyProductionEnergyRestorer.querySnapshot(timedata, COMPONENT_ID, now)
				.orElseThrow();

		assertEquals(Long.valueOf(120_000L), snapshot.totalBaselineWh());
		assertEquals(validTimestamp.toInstant(), snapshot.lastTimestamp());
	}

	@Test
	public void testRetryDelayMatchesRestorePolicy() {
		assertEquals(Duration.ofMinutes(1), HypontechDailyProductionEnergyRestorer.RETRY_DELAY);
	}

	@Test
	public void testOnlyOneQueryRunsAtATime() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var executor = new QueuedExecutor();
		var timedata = validTimedata(clock);
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, executor, () -> timedata);
		restorer.activate();

		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));

		assertEquals(1, executor.size());
		assertEquals(0, timedata.queryCount);
		executor.runNext();
		assertEquals(1, timedata.queryCount);
	}

	@Test
	public void testQueryFailureSchedulesRetryAfterOneMinute() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var timedata = validTimedata(clock);
		timedata.failuresRemaining = 1;
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, Runnable::run, () -> timedata);
		restorer.activate();

		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(1, timedata.queryCount);
		clock.advance(Duration.ofSeconds(59));
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(1, timedata.queryCount);
		clock.advance(Duration.ofSeconds(1));
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(2, timedata.queryCount);
		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 4)).isPresent());
	}

	@Test
	public void testTimedataAvailabilityAllowsImmediateRetry() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var timedata = validTimedata(clock);
		var timedataReference = new AtomicReference<Timedata>();
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, Runnable::run, timedataReference::get);
		restorer.activate();

		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		timedataReference.set(timedata);
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(0, timedata.queryCount);
		restorer.onTimedataAvailable();
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(1, timedata.queryCount);
	}

	@Test
	public void testTimedataChangeSkipsQueuedOldProviderAndAllowsImmediateReplacement() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var executor = new QueuedExecutor();
		var timedataA = validTimedata(clock);
		var timedataB = validTimedata(clock);
		var timedataReference = new AtomicReference<Timedata>(timedataA);
		var timedataAccessLock = new Object();
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, executor, timedataReference::get,
				timedataAccessLock);
		restorer.activate();

		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		synchronized (timedataAccessLock) {
			timedataReference.set(timedataB);
		}
		restorer.onTimedataChanged();
		executor.runNext();

		assertEquals(0, timedataA.queryCount);
		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 4)).isEmpty());
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		executor.runNext();
		assertEquals(1, timedataB.queryCount);
		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 4)).isPresent());
	}

	@Test
	public void testQueuedResultFromPreviousDateIsDiscarded() {
		var clock = new MutableClock(Instant.parse("2026-08-04T23:59:00Z"), ZoneOffset.UTC);
		var executor = new QueuedExecutor();
		var timedata = validTimedata(clock);
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, executor, () -> timedata);
		restorer.activate();
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));

		clock.advance(Duration.ofMinutes(2));
		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 5)).isEmpty());
		executor.runNext();

		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 5)).isEmpty());
	}

	@Test
	public void testQueuedResultAfterDeactivateIsDiscarded() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var executor = new QueuedExecutor();
		var timedata = validTimedata(clock);
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, executor, () -> timedata);
		restorer.activate();
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));

		restorer.deactivate();
		executor.runNext();

		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 4)).isEmpty());
	}

	@Test
	public void testSuccessfulSnapshotCanOnlyBePolledOnceAndBlocksFurtherRequests() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var timedata = validTimedata(clock);
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, Runnable::run, () -> timedata);
		restorer.activate();

		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 4)).isPresent());
		assertTrue(restorer.pollSnapshot(LocalDate.of(2026, 8, 4)).isEmpty());
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(1, timedata.queryCount);
	}

	@Test
	public void testExecutorRejectionSchedulesRetry() {
		var clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
		var executor = new RejectingExecutor();
		var timedata = validTimedata(clock);
		var restorer = new HypontechDailyProductionEnergyRestorer(clock, executor, () -> timedata);
		restorer.activate();

		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(1, executor.executionCount);
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(1, executor.executionCount);
		clock.advance(Duration.ofMinutes(1));
		restorer.request(COMPONENT_ID, LocalDate.of(2026, 8, 4));
		assertEquals(2, executor.executionCount);
	}

	private static void addPair(TestTimedata timedata, ZonedDateTime timestamp, long total, long today) {
		timedata.add(timestamp, TOTAL, new JsonPrimitive(total));
		timedata.add(timestamp, TODAY, new JsonPrimitive(today));
	}

	private static CountingTimedata validTimedata(Clock clock) {
		var timedata = new CountingTimedata("timedata0");
		var now = ZonedDateTime.now(clock);
		addPair(timedata, now.minusMinutes(1), 125_000L, 5_000L);
		return timedata;
	}

	private static class TestTimedata extends AbstractDummyOpenemsComponent<TestTimedata> implements Timedata {
		private final SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> data = new TreeMap<>();

		private TestTimedata(String id) {
			super(id, OpenemsComponent.ChannelId.values(), Timedata.ChannelId.values());
		}

		@Override
		protected TestTimedata self() {
			return this;
		}

		protected final void add(ZonedDateTime timestamp, ChannelAddress channelAddress, JsonElement value) {
			this.data.computeIfAbsent(timestamp, ignore -> new TreeMap<>()).put(channelAddress, value);
		}

		@Override
		public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricData(String edgeId,
				ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels, Resolution resolution)
				throws OpenemsNamedException {
			var result = new TreeMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>>();
			for (Entry<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> entry : this.data
					.subMap(fromDate, toDate).entrySet()) {
				var row = new TreeMap<ChannelAddress, JsonElement>();
				for (var channelAddress : channels) {
					row.put(channelAddress, entry.getValue().get(channelAddress));
				}
				result.put(entry.getKey(), row);
			}
			return result;
		}

		@Override
		public SortedMap<ChannelAddress, JsonElement> queryHistoricEnergy(String edgeId, ZonedDateTime fromDate,
				ZonedDateTime toDate, Set<ChannelAddress> channels) throws OpenemsNamedException {
			throw new NotImplementedException("Not implemented for this test");
		}

		@Override
		public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricEnergyPerPeriod(
				String edgeId, ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels,
				Resolution resolution) throws OpenemsNamedException {
			throw new NotImplementedException("Not implemented for this test");
		}

		@Override
		public SortedMap<Long, SortedMap<ChannelAddress, JsonElement>> queryResendData(ZonedDateTime fromDate,
				ZonedDateTime toDate, Set<ChannelAddress> channels) throws OpenemsNamedException {
			throw new NotImplementedException("Not implemented for this test");
		}

		@Override
		public CompletableFuture<Optional<Object>> getLatestValue(ChannelAddress channelAddress) {
			return CompletableFuture.completedFuture(Optional.empty());
		}

		@Override
		public Timeranges getResendTimeranges(ChannelAddress notSendChannel, long lastResendTimestamp)
				throws OpenemsNamedException {
			throw new NotImplementedException("Not implemented for this test");
		}
	}

	private static final class RecordingTimedata extends TestTimedata {
		private ZonedDateTime fromDate;
		private ZonedDateTime toDate;
		private Set<ChannelAddress> channels;
		private Resolution resolution;

		private RecordingTimedata(String id) {
			super(id);
		}

		@Override
		public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricData(String edgeId,
				ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels, Resolution resolution)
				throws OpenemsNamedException {
			this.fromDate = fromDate;
			this.toDate = toDate;
			this.channels = channels;
			this.resolution = resolution;
			assertFalse(channels.isEmpty());
			return super.queryHistoricData(edgeId, fromDate, toDate, channels, resolution);
		}
	}

	private static final class ReverseTimedata extends TestTimedata {

		private ReverseTimedata(String id) {
			super(id);
		}

		@Override
		public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricData(String edgeId,
				ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels, Resolution resolution)
				throws OpenemsNamedException {
			var result = new TreeMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>>(
					Comparator.reverseOrder());
			result.putAll(super.queryHistoricData(edgeId, fromDate, toDate, channels, resolution));
			return result;
		}
	}

	private static final class NullRowTimedata extends TestTimedata {
		private final ZonedDateTime nullTimestamp;

		private NullRowTimedata(String id, ZonedDateTime nullTimestamp) {
			super(id);
			this.nullTimestamp = nullTimestamp;
		}

		@Override
		public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricData(String edgeId,
				ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels, Resolution resolution)
				throws OpenemsNamedException {
			var result = super.queryHistoricData(edgeId, fromDate, toDate, channels, resolution);
			result.put(this.nullTimestamp, null);
			return result;
		}
	}

	private static final class CountingTimedata extends TestTimedata {
		private int queryCount;
		private int failuresRemaining;

		private CountingTimedata(String id) {
			super(id);
		}

		@Override
		public SortedMap<ZonedDateTime, SortedMap<ChannelAddress, JsonElement>> queryHistoricData(String edgeId,
				ZonedDateTime fromDate, ZonedDateTime toDate, Set<ChannelAddress> channels, Resolution resolution)
				throws OpenemsNamedException {
			this.queryCount++;
			if (this.failuresRemaining > 0) {
				this.failuresRemaining--;
				throw new IllegalStateException("Simulated query failure");
			}
			return super.queryHistoricData(edgeId, fromDate, toDate, channels, resolution);
		}
	}

	private static final class QueuedExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			this.tasks.add(command);
		}

		private int size() {
			return this.tasks.size();
		}

		private void runNext() {
			this.tasks.remove().run();
		}
	}

	private static final class RejectingExecutor implements Executor {
		private int executionCount;

		@Override
		public void execute(Runnable command) {
			this.executionCount++;
			throw new RejectedExecutionException("Simulated rejection");
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;
		private final ZoneId zone;

		private MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		@Override
		public ZoneId getZone() {
			return this.zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(this.instant, zone);
		}

		@Override
		public Instant instant() {
			return this.instant;
		}

		private void advance(Duration duration) {
			this.instant = this.instant.plus(duration);
		}
	}
}
