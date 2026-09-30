package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.InitialBaselineReferenceDatasetService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

public class InitialBaselineReferenceCacheTest {

	@Test
	public void refreshPublishesOneImmutableHealthySnapshot() throws Exception {
		var first = dataset("str1");
		var cache = cache(new FixedService(List.of(first)));
		cache.refresh();

		var snapshot = cache.snapshot();
		assertTrue(snapshot.healthy());
		assertNotNull(snapshot.lastRefreshAttemptAt());
		assertNotNull(snapshot.lastSuccessfulRefreshAt());
		assertNull(snapshot.lastError());
		assertEquals(first, snapshot.byStringId().get(first.stringId()));
		assertEquals(first, snapshot.byStringKey().get(first.stringKey()));
		assertThrows(UnsupportedOperationException.class,
				() -> snapshot.byStringKey().put("other", first));
	}

	@Test
	public void failedOrInvalidRefreshRetainsTheLastKnownGoodDataset() throws Exception {
		var first = dataset("str1");
		var service = new MutableService(List.of(first));
		var cache = cache(service);
		cache.refreshSafely();
		var successfulAt = cache.snapshot().lastSuccessfulRefreshAt();

		service.failure = new IllegalStateException("invalid reference");
		cache.refreshSafely();

		var snapshot = cache.snapshot();
		assertFalse(snapshot.healthy());
		assertEquals(first, snapshot.byStringKey().get("str1"));
		assertEquals(successfulAt, snapshot.lastSuccessfulRefreshAt());
		assertTrue(snapshot.lastError().contains("invalid reference"));
	}

	@Test
	public void duplicateStringIdentityRejectsTheWholeRefresh() throws Exception {
		var first = dataset("str1");
		var duplicateKey = dataset("str1");
		var cache = cache(new FixedService(List.of(first, duplicateKey)));

		assertThrows(IllegalStateException.class, cache::refresh);
		assertTrue(cache.snapshot().byStringId().isEmpty());
		assertTrue(cache.snapshot().byStringKey().isEmpty());
	}

	@Test
	public void concurrentReadersOnlyObserveCompleteSnapshots() throws Exception {
		var first = dataset("str1");
		var second = dataset("str2");
		var calls = new AtomicInteger();
		var cache = cache(new InitialBaselineReferenceDatasetService() {
			@Override
			public List<InitialBaselineReferenceDataset> loadAllActive() {
				return calls.getAndIncrement() % 2 == 0 ? List.of(first) : List.of(second);
			}
		});
		cache.refresh();
		var failure = new AtomicReference<Throwable>();
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(4);
		try {
			executor.submit(() -> run(start, failure, () -> {
				for (int i = 0; i < 1_000; i++) cache.refresh();
			}));
			for (int reader = 0; reader < 3; reader++) {
				executor.submit(() -> run(start, failure, () -> {
					for (int i = 0; i < 20_000; i++) assertCoherent(cache.snapshot());
				}));
			}
			start.countDown();
			executor.shutdown();
			assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
			if (failure.get() != null) throw new AssertionError(failure.get());
		} finally {
			executor.shutdownNow();
		}
	}

	private static void assertCoherent(
			io.openems.edge.bms.service.reference.contracts.InitialBaselineReferenceDatasetProvider.Snapshot snapshot) {
		assertEquals(1, snapshot.byStringId().size());
		assertEquals(1, snapshot.byStringKey().size());
		var value = snapshot.byStringId().values().iterator().next();
		assertEquals(value, snapshot.byStringKey().get(value.stringKey()));
	}

	private static void run(CountDownLatch start, AtomicReference<Throwable> failure, CheckedRun action) {
		try {
			start.await();
			action.run();
		} catch (Throwable e) {
			failure.compareAndSet(null, e);
		}
	}

	private static InitialBaselineReferenceCache cache(InitialBaselineReferenceDatasetService service)
			throws ReflectiveOperationException {
		var cache = new InitialBaselineReferenceCache();
		Field field = InitialBaselineReferenceCache.class.getDeclaredField("service");
		field.setAccessible(true);
		field.set(cache, service);
		return cache;
	}

	private static InitialBaselineReferenceDataset dataset(String stringKey) {
		var now = Instant.parse("2026-09-04T00:00:00Z");
		return new InitialBaselineReferenceDataset(Math.abs(stringKey.hashCode()) + 1L, UUID.randomUUID(), stringKey,
				1, 200.0, now, now, UUID.randomUUID(), null, null, null, Map.of(), List.of(), List.of());
	}

	@FunctionalInterface
	private interface CheckedRun {
		void run() throws Exception;
	}

	private static class FixedService extends InitialBaselineReferenceDatasetService {
		private final List<InitialBaselineReferenceDataset> values;

		private FixedService(List<InitialBaselineReferenceDataset> values) {
			this.values = values;
		}

		@Override
		public List<InitialBaselineReferenceDataset> loadAllActive() {
			return this.values;
		}
	}

	private static final class MutableService extends FixedService {
		private RuntimeException failure;

		private MutableService(List<InitialBaselineReferenceDataset> values) {
			super(values);
		}

		@Override
		public List<InitialBaselineReferenceDataset> loadAllActive() {
			if (this.failure != null) throw this.failure;
			return super.loadAllActive();
		}
	}
}
