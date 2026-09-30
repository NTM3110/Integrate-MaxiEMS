package io.openems.edge.bridge.iec6205621;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.openems.edge.common.taskmanager.Priority;

public class Iec6205621WorkerTest {

	private static final Iec6205621Transport TRANSPORT = new Iec6205621Transport() {
		@Override
		public void send(byte[] bytes) throws IOException {
		}

		@Override
		public int readByte(long deadlineNanos) throws IOException {
			return -1;
		}

		@Override
		public String name() {
			return "test";
		}
	};

	@Test
	public void serializesOneShotTransactionsOnOneWorker() throws Exception {
		var active = new AtomicInteger();
		var maximumActive = new AtomicInteger();
		var parent = mock(Iec6205621Component.class);
		var worker = new Iec6205621Worker(task -> task.execute(TRANSPORT));
		worker.addProtocol("meter0", new Iec6205621Protocol(parent));
		worker.activate("iec-test-serialization");
		try {
			var futures = new ArrayList<CompletableFuture<Integer>>();
			for (int i = 0; i < 12; i++) {
				var task = new Iec6205621OneShotTask<Integer>(Priority.HIGH, transport -> {
					int current = active.incrementAndGet();
					maximumActive.accumulateAndGet(current, Math::max);
					try {
						Thread.sleep(5);
					} finally {
						active.decrementAndGet();
					}
					return current;
				});
				worker.submitOneShot("meter0", task);
				futures.add(task.future());
			}
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
			assertEquals(1, maximumActive.get());
		} finally {
			worker.deactivate();
		}
	}

	@Test
	public void boundedOneShotBurstDoesNotStarveRecurringTask() throws Exception {
		var order = Collections.synchronizedList(new ArrayList<String>());
		var recurringExecuted = new CountDownLatch(1);
		var parent = mock(Iec6205621Component.class);
		var recurring = new AbstractIec6205621Task(Priority.LOW, 0, finished -> Long.MAX_VALUE) {
			@Override
			public void execute(Iec6205621Transport transport) {
				order.add("recurring");
				recurringExecuted.countDown();
			}
		};
		var worker = new Iec6205621Worker(task -> task.execute(TRANSPORT));
		worker.addProtocol("meter0", new Iec6205621Protocol(parent, recurring));
		for (int i = 0; i < 10; i++) {
			int number = i;
			var oneShot = new Iec6205621OneShotTask<Integer>(Priority.HIGH, transport -> {
				order.add("one-shot-" + number);
				return number;
			});
			worker.submitOneShot("meter0", oneShot);
		}
		worker.activate("iec-test-fairness");
		try {
			assertTrue(recurringExecuted.await(3, TimeUnit.SECONDS));
			assertTrue("Recurring task must run after at most four one-shot tasks", order.indexOf("recurring") <= 4);
		} finally {
			worker.deactivate();
		}
	}

	@Test
	public void removingProtocolCancelsItsQueuedOneShotTasks() throws Exception {
		var firstStarted = new CountDownLatch(1);
		var releaseFirst = new CountDownLatch(1);
		var parent = mock(Iec6205621Component.class);
		var worker = new Iec6205621Worker(task -> task.execute(TRANSPORT));
		worker.addProtocol("meter0", new Iec6205621Protocol(parent));
		var first = new Iec6205621OneShotTask<Integer>(Priority.HIGH, transport -> {
			firstStarted.countDown();
			releaseFirst.await(3, TimeUnit.SECONDS);
			return 1;
		});
		var queued = new Iec6205621OneShotTask<Integer>(Priority.HIGH, transport -> 2);
		worker.submitOneShot("meter0", first);
		worker.submitOneShot("meter0", queued);
		worker.activate("iec-test-removal");
		try {
			assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
			worker.removeProtocol("meter0");
			assertTrue(queued.future().isCompletedExceptionally());
		} finally {
			releaseFirst.countDown();
			worker.deactivate();
		}
	}

	@Test
	public void failureOfOneComponentDoesNotBlockAnotherComponent() throws Exception {
		var parentA = mock(Iec6205621Component.class);
		var parentB = mock(Iec6205621Component.class);
		var worker = new Iec6205621Worker(task -> task.execute(TRANSPORT));
		worker.addProtocol("meterA", new Iec6205621Protocol(parentA));
		worker.addProtocol("meterB", new Iec6205621Protocol(parentB));
		var failed = new Iec6205621OneShotTask<Integer>(Priority.HIGH, transport -> {
			throw new IOException("simulated failure");
		});
		var succeeded = new Iec6205621OneShotTask<Integer>(Priority.HIGH, transport -> 7);
		worker.submitOneShot("meterA", failed);
		worker.submitOneShot("meterB", succeeded);
		worker.activate("iec-test-recovery");
		try {
			assertEquals(Integer.valueOf(7), succeeded.future().get(3, TimeUnit.SECONDS));
			assertTrue(failed.future().isCompletedExceptionally());
			verify(parentA)._setIec6205621CommunicationFailed(true);
			verify(parentB)._setIec6205621CommunicationFailed(false);
		} finally {
			worker.deactivate();
		}
	}

	@Test
	public void recurringTasksUseRoundRobinAcrossComponents() throws Exception {
		var order = Collections.synchronizedList(new ArrayList<String>());
		var executed = new CountDownLatch(2);
		var worker = new Iec6205621Worker(task -> task.execute(TRANSPORT));
		worker.addProtocol("meterA", new Iec6205621Protocol(mock(Iec6205621Component.class),
				recordingTask("A", order, executed)));
		worker.addProtocol("meterB", new Iec6205621Protocol(mock(Iec6205621Component.class),
				recordingTask("B", order, executed)));
		worker.activate("iec-test-round-robin");
		try {
			assertTrue(executed.await(3, TimeUnit.SECONDS));
			assertEquals(List.of("A", "B"), new ArrayList<>(order));
		} finally {
			worker.deactivate();
		}
	}

	private static AbstractIec6205621Task recordingTask(String name, List<String> order, CountDownLatch executed) {
		return new AbstractIec6205621Task(Priority.HIGH, 0, finished -> Long.MAX_VALUE) {
			@Override
			public void execute(Iec6205621Transport transport) {
				order.add(name);
				executed.countDown();
			}
		};
	}
}
