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

public class ReadBillingValuesBatchTask extends AbstractDlmsTask {

	private static final long RESULT_TIMEOUT_MINUTES = 30;

	private final Logger log = LoggerFactory.getLogger(ReadBillingValuesBatchTask.class);
	private final CompletableFuture<Map<String, Object[]>> result = new CompletableFuture<>();
	private final List<DlmsBatchTarget> targets;

	public ReadBillingValuesBatchTask(Priority priority, List<DlmsBatchTarget> targets) {
		super(priority);
		this.targets = targets;
	}

	@Override
	public ExecuteState execute(AbstractDlmsBridge bridge) {
		var valuesByOutstation = new LinkedHashMap<String, Object[]>();
		try {
			for (var target : this.targets) {
				this.log.info("Reading DLMS billing values batch item component [{}] outstation [{}]",
						target.componentId(), target.outstation());
				valuesByOutstation.put(target.outstation(),
						bridge.readBillingValuesForTargetAddress(target.componentId(), target.serverAddress()));
			}
			this.result.complete(valuesByOutstation);
			return ExecuteState.OK;
		} catch (Exception e) {
			this.log.error("Reading DLMS billing values batch failed: {}", e.getMessage(), e);
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
			throw new Exception("Timed out waiting for DLMS billing values batch task", e);
		}
	}
}
