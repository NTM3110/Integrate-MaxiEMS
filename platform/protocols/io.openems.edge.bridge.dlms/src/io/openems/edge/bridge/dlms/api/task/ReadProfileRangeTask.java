package io.openems.edge.bridge.dlms.api.task;

import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.common.taskmanager.Priority;

public class ReadProfileRangeTask extends AbstractDlmsTask {

	private static final long RESULT_TIMEOUT_MINUTES = 15;

	private final Logger log = LoggerFactory.getLogger(ReadProfileRangeTask.class);
	private final CompletableFuture<Object[]> result = new CompletableFuture<>();
	private final String profileObis;
	private final Date start;
	private final Date end;

	public ReadProfileRangeTask(Priority priority, String profileObis, Date start, Date end) {
		super(priority);
		this.profileObis = profileObis;
		this.start = start;
		this.end = end;
	}

	@Override
	public ExecuteState execute(AbstractDlmsBridge bridge) {
		try {
			this.log.info("Reading DLMS profile [{}] for component [{}] from [{}] to [{}]", this.profileObis,
					this.getParent() != null ? this.getParent().id() : "-", this.start, this.end);
			var rows = bridge.readProfileForTarget(this.getParent(), this.profileObis, this.start, this.end);
			this.log.info("Finished reading DLMS profile [{}] for component [{}]", this.profileObis,
					this.getParent() != null ? this.getParent().id() : "-");
			this.result.complete(rows);
			return ExecuteState.OK;
		} catch (Exception e) {
			this.log.error("Reading DLMS profile [{}] failed for component [{}]: {}", this.profileObis,
					this.getParent() != null ? this.getParent().id() : "-", e.getMessage(), e);
			this.result.completeExceptionally(e);
			return new ExecuteState.Error(e);
		}
	}

	public Object[] await() throws Exception {
		try {
			return this.result.get(RESULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} catch (ExecutionException e) {
			var cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw new Exception(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		} catch (TimeoutException e) {
			throw new Exception("Timed out waiting for DLMS profile task", e);
		}
	}
}
