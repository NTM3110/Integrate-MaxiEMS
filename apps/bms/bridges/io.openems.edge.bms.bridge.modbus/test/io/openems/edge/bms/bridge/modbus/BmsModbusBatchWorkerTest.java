package io.openems.edge.bms.bridge.modbus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.Outcome;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.common.taskmanager.Priority;

public class BmsModbusBatchWorkerTest {
	@Test
	public void readsAllSourcesInOrderAndCapsBackoffAtTenBatches() {
		var reads = new ArrayList<String>();
		var failures = new AtomicInteger();
		var clock = new AtomicLong(1000);
		var worker = new BmsModbusBatchWorker(task -> {
			var id = task.getParent().id();
			reads.add(id);
			if (id.equals("b") && failures.getAndIncrement() < 3) {
				return new ExecuteState.Error(new Exception("timeout"));
			}
			return ExecuteState.OK;
		}, elements -> {}, () -> 1, clock::get, batch -> {}, batch -> true);
		worker.addProtocol("b", protocol("b"));
		worker.addProtocol("a", protocol("a"));
		var first = worker.executeBatch();
		assertEquals(List.of("a", "b"), reads);
		assertEquals(Outcome.FAILED, first.outcomes().get("b"));
		assertEquals(Outcome.FAILED, worker.executeBatch().outcomes().get("b"));
		assertEquals(Outcome.SKIPPED, worker.executeBatch().outcomes().get("b"));
		assertEquals(Outcome.FAILED, worker.executeBatch().outcomes().get("b"));
		for (var cycle = 5; cycle <= 13; cycle++) {
			assertEquals(Outcome.SKIPPED, worker.executeBatch().outcomes().get("b"));
		}
		var recovery = worker.executeBatch();
		assertEquals(14, recovery.batchId());
		assertEquals(Outcome.SUCCESS, recovery.outcomes().get("b"));
		assertEquals(Outcome.SUCCESS, worker.executeBatch().outcomes().get("b"));
		assertEquals(first.generation(), recovery.generation());
	}

	@Test
	public void registrationChangesApplyToNextBatchWithoutReadDurationCutoff() {
		var clock = new AtomicLong(1000);
		var reads = new ArrayList<String>();
		var worker = new BmsModbusBatchWorker(task -> {
			reads.add(task.getParent().id());
			clock.addAndGet(20_001);
			return ExecuteState.OK;
		}, elements -> {}, () -> 1, clock::get, batch -> {}, batch -> true);
		worker.addProtocol("a", protocol("a"));
		var first = worker.executeBatch();
		assertEquals(List.of("a"), reads);
		assertEquals(21_001, first.completedAt());
		worker.addProtocol("b", protocol("b"));
		var second = worker.executeBatch();
		assertEquals(2, second.outcomes().size());
		assertEquals(List.of("a", "a", "b"), reads);
		assertNotEquals(first.batchId(), second.batchId());
		worker.removeProtocol("a");
		assertEquals(List.of("b"), List.copyOf(worker.executeBatch().outcomes().keySet()));
	}

	@Test
	public void nextBatchWaitsForCalculatorEvenWhenTenSecondsHavePassed() throws Exception {
		var clock = new AtomicLong(1000);
		var reads = new AtomicInteger();
		var completed = new CountDownLatch(1);
		var acknowledged = new AtomicBoolean();
		var worker = new BmsModbusBatchWorker(task -> {
			reads.incrementAndGet();
			return ExecuteState.OK;
		}, elements -> {}, () -> 1, clock::get,
				batch -> completed.countDown(), batch -> acknowledged.get());
		worker.addProtocol("a", protocol("a"));
		worker.start("test");
		try {
			assertEquals(true, completed.await(2, TimeUnit.SECONDS));
			clock.set(100_000);
			Thread.sleep(250);
			assertEquals(1, reads.get());
			acknowledged.set(true);
			var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (reads.get() < 2 && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertEquals(2, reads.get());
		} finally {
			worker.stop();
		}
	}

	private static ModbusProtocol protocol(String id) {
		var parent = mock(AbstractOpenemsModbusComponent.class);
		when(parent.id()).thenReturn(id);
		return new ModbusProtocol(parent,
				new FC3ReadRegistersTask(1, Priority.HIGH, new UnsignedWordElement(1)));
	}
}
