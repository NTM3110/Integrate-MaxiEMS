package io.openems.edge.bridge.edmi.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.influxdb.client.write.Point;

import io.openems.common.worker.AbstractWorker;
import io.openems.edge.bridge.edmi.EdmiBridge;

public class EdmiWorker extends AbstractWorker {
	private final Logger log = LoggerFactory.getLogger(EdmiWorker.class);
	private final DelayQueue<ScheduledEdmiTask> taskQueue = new DelayQueue<>();
	private final ConcurrentHashMap<EdmiTask, ScheduledEdmiTask> scheduledTasks = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();
	private final Consumer<Point> influxWriter;
	private final long errorRetryDelayMillis;

	public EdmiWorker(EdmiBridge bridge, Consumer<Point> influxWriter, long errorRetryDelayMillis) {
		this.influxWriter = influxWriter;
		this.errorRetryDelayMillis = Math.max(1_000L, errorRetryDelayMillis);
	}

	@Override
	protected void forever() throws InterruptedException {
		ScheduledEdmiTask scheduledTask = this.taskQueue.take();
		EdmiTask task = scheduledTask.task();

		try {
			task.execute();
		} catch (Exception e) {
			if (scheduledTask.repeating() && task.getNextRunTime() <= System.currentTimeMillis()) {
				task.setNextRunTime(System.currentTimeMillis() + this.errorRetryDelayMillis);
			}
			this.log.error("Error executing EDMI Task [" + task + "]: " + e.getMessage(), e);
		}

		if (scheduledTask.repeating() && this.scheduledTasks.replace(task, scheduledTask, this.newRepeatingTask(task))) {
			this.taskQueue.put(this.scheduledTasks.get(task));
		}
	}

	@Override
	protected int getCycleTime() {
		return 0; // Handled by DelayQueue.take()
	}

	public void addTask(EdmiTask task) {
		if (task.getNextRunTime() <= 0) {
			task.setNextRunTime(System.currentTimeMillis());
		}
		ScheduledEdmiTask scheduledTask = this.newRepeatingTask(task);
		ScheduledEdmiTask previous = this.scheduledTasks.put(task, scheduledTask);
		if (previous != null) {
			this.taskQueue.remove(previous);
		}
		this.taskQueue.put(scheduledTask);
	}

	public void removeTask(EdmiTask task) {
		ScheduledEdmiTask scheduledTask = this.scheduledTasks.remove(task);
		if (scheduledTask != null) {
			this.taskQueue.remove(scheduledTask);
		}
	}

	public void writeToInflux(Point point) {
		this.influxWriter.accept(point);
	}

	public <T> T executeImmediately(Callable<T> callable) throws Exception {
		var task = new OneShotEdmiTask<>(callable);
		this.taskQueue.put(new ScheduledEdmiTask(task, false, this.sequence.getAndIncrement()));
		return task.get();
	}

	private ScheduledEdmiTask newRepeatingTask(EdmiTask task) {
		return new ScheduledEdmiTask(task, true, this.sequence.getAndIncrement());
	}

	private record ScheduledEdmiTask(EdmiTask task, boolean repeating, long sequence) implements Delayed {

		@Override
		public long getDelay(TimeUnit unit) {
			long delayMillis = this.task.getNextRunTime() - System.currentTimeMillis();
			return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
		}

		@Override
		public int compareTo(Delayed other) {
			if (other == this) {
				return 0;
			}
			ScheduledEdmiTask scheduledOther = (ScheduledEdmiTask) other;
			int byTime = Long.compare(this.task.getNextRunTime(), scheduledOther.task.getNextRunTime());
			if (byTime != 0) {
				return byTime;
			}
			int byPriority = this.task.getPriority().compareTo(scheduledOther.task.getPriority());
			if (byPriority != 0) {
				return byPriority;
			}
			return Long.compare(this.sequence, scheduledOther.sequence);
		}
	}

	private static final class OneShotEdmiTask<T> extends AbstractEdmiTask {
		private final Callable<T> callable;
		private final CompletableFuture<T> result = new CompletableFuture<>();

		private OneShotEdmiTask(Callable<T> callable) {
			super(io.openems.edge.common.taskmanager.Priority.HIGH);
			this.callable = callable;
			this.setNextRunTime(0);
		}

		@Override
		public void execute() throws Exception {
			try {
				this.result.complete(this.callable.call());
			} catch (Exception e) {
				this.result.completeExceptionally(e);
				throw e;
			}
		}

		private T get() throws Exception {
			try {
				return this.result.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw e;
			} catch (ExecutionException e) {
				var cause = e.getCause();
				if (cause instanceof Exception exception) {
					throw exception;
				}
				throw new RuntimeException(cause);
			}
		}
	}
}
