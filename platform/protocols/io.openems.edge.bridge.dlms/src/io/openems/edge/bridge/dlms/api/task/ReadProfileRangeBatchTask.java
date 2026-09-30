package io.openems.edge.bridge.dlms.api.task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.bridge.dlms.api.DlmsBatchTarget;
import io.openems.edge.common.taskmanager.Priority;

public class ReadProfileRangeBatchTask extends AbstractDlmsTask {

	private static final long RESULT_TIMEOUT_MINUTES = 60;

	private final Logger log = LoggerFactory.getLogger(ReadProfileRangeBatchTask.class);
	private final CompletableFuture<Map<String, Object[]>> result = new CompletableFuture<>();
	private final List<DlmsBatchTarget> targets;

	public ReadProfileRangeBatchTask(Priority priority, List<DlmsBatchTarget> targets) {
		super(priority);
		this.targets = targets;
	}

	@Override
	public ExecuteState execute(AbstractDlmsBridge bridge) {
		var rowsByOutstation = new LinkedHashMap<String, Object[]>();
		try {
			for (var target : this.targets) {
				this.log.info("Reading DLMS profile batch item component [{}] outstation [{}] from [{}] to [{}]",
						target.componentId(), target.outstation(), target.start(), target.end());
				rowsByOutstation.put(target.outstation(), bridge.readProfileForTargetAddress(target.componentId(),
						target.serverAddress(), target.profileObis(), target.start(), target.end()));
			}
			this.result.complete(rowsByOutstation);
			return ExecuteState.OK;
		} catch (Exception e) {
			this.log.error("Reading DLMS profile batch failed: {}", e.getMessage(), e);
			this.result.completeExceptionally(e);
			return new ExecuteState.Error(e);
		}
	}

	public Map<String, Object[]> await() throws Exception {
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
			throw new Exception("Timed out waiting for DLMS profile batch task", e);
		}
	}
}
