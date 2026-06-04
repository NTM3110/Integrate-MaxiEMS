package io.openems.edge.bridge.dlms.api.task;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.common.taskmanager.Priority;

public class ReadBillingValuesTask extends AbstractDlmsTask {

	private static final long RESULT_TIMEOUT_MINUTES = 10;

	private final Logger log = LoggerFactory.getLogger(ReadBillingValuesTask.class);
	private final CompletableFuture<Object[]> result = new CompletableFuture<>();

	public ReadBillingValuesTask(Priority priority) {
		super(priority);
	}

	@Override
	public ExecuteState execute(AbstractDlmsBridge bridge) {
		try {
			this.log.info("Reading DLMS billing values for component [{}]", this.getParent() != null ? this.getParent().id() : "-");
			var values = bridge.readBillingValuesForTarget(this.getParent());
			this.log.info("Finished reading DLMS billing values for component [{}]", this.getParent() != null ? this.getParent().id() : "-");
			this.result.complete(values);
			return ExecuteState.OK;
		} catch (Exception e) {
			this.log.error("Reading DLMS billing values failed for component [{}]: {}",
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
			throw new Exception("Timed out waiting for DLMS billing values task", e);
		}
	}
}
