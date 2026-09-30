package io.openems.edge.bridge.dlms.api;


import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.dlms.api.worker.DlmsWorker;
import io.openems.edge.bridge.dlms.api.task.ReadBillingValuesTask;
import io.openems.edge.bridge.dlms.api.task.ReadBillingValuesBatchTask;
import io.openems.edge.bridge.dlms.api.task.ReadProfileRangeTask;
import io.openems.edge.bridge.dlms.api.task.ReadProfileRangeBatchTask;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import io.openems.edge.common.event.EdgeEventConstants;


public abstract class AbstractDlmsBridge extends AbstractOpenemsComponent 
				implements BridgeDlms, EventHandler {

	private final Logger log = LoggerFactory.getLogger(AbstractDlmsBridge.class);
	protected final DlmsWorker worker = new DlmsWorker(task -> task.execute(this));

	protected AbstractDlmsBridge(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	protected void activate(ComponentContext context, String id, String alias, boolean enabled) {
		super.activate(context, id, alias, enabled);
		if (enabled) {
			this.worker.activate(id);
		}
	}


	@Override
	public void handleEvent(Event event) {
		// DLMS worker is driven by its own background thread, not by OpenEMS cycles.
		// This handler is kept here in case future cycle-aware logic is needed.
	}

	@Override
	protected void deactivate() {
		super.deactivate();
		this.worker.deactivate();
	}

	@Override
	public void addProtocol(String sourceId, DlmsProtocol protocol) {
		this.worker.addProtocol(sourceId, protocol);
	}

	@Override
	public void removeProtocol(String sourceId) {
		this.worker.removeProtocol(sourceId);
	}

	@Override
	public void retryDlmsCommunication(String sourceId) {
		this.worker.retryDlmsCommunication(sourceId);
	}

	/**
	 * Reads a DLMS object value.
	 *
	 * @param obis           the OBIS code
	 * @param attributeIndex the attribute index
	 * @return the value
	 * @throws Exception on error
	 */
	public abstract Object read(String obis, int attributeIndex) throws Exception;

	public Object[] readBillingValuesForTarget(DlmsComponent parent) throws Exception {
		this.applyTarget(parent);
		return this.readBillingValues();
	}

	public Object[] readProfileForTarget(DlmsComponent parent, String obis, Date start, Date end) throws Exception {
		this.applyTarget(parent);
		return this.readProfile(obis, start, end);
	}

	public Object[] readBillingValuesForTargetAddress(String componentId, int serverAddress) throws Exception {
		this.applyTargetAddress(serverAddress);
		return this.readBillingValues();
	}

	public Object[] readProfileForTargetAddress(String componentId, int serverAddress, String obis, Date start,
			Date end) throws Exception {
		this.applyTargetAddress(serverAddress);
		return this.readProfile(obis, start, end);
	}

	public Object[] readBillingValuesViaWorker(DlmsComponent parent) throws Exception {
		this.requireWorker("billing values", parent);
		var task = new ReadBillingValuesTask(Priority.HIGH);
		task.setParent(parent);
		this.log.info("Queueing DLMS billing values task for component [{}]", parent != null ? parent.id() : "-");
		this.worker.submitOneShot(task);
		return task.await();
	}

	public Object[] readProfileViaWorker(DlmsComponent parent, String obis, Date start, Date end) throws Exception {
		this.requireWorker("profile", parent);
		var task = new ReadProfileRangeTask(Priority.HIGH, obis, start, end);
		task.setParent(parent);
		this.log.info("Queueing DLMS profile task for component [{}]", parent != null ? parent.id() : "-");
		this.worker.submitOneShot(task);
		return task.await();
	}

	public Map<String, Object[]> readBillingValuesBatchViaWorker(List<DlmsBatchTarget> targets) throws Exception {
		this.requireWorker("billing values batch", null);
		var task = new ReadBillingValuesBatchTask(Priority.HIGH, targets);
		this.log.info("Queueing DLMS billing values batch task with [{}] target(s)", targets.size());
		this.worker.submitOneShot(task);
		return task.await();
	}

	public Map<String, Object[]> readProfileBatchViaWorker(List<DlmsBatchTarget> targets) throws Exception {
		this.requireWorker("profile batch", null);
		var task = new ReadProfileRangeBatchTask(Priority.HIGH, targets);
		this.log.info("Queueing DLMS profile batch task with [{}] target(s)", targets.size());
		this.worker.submitOneShot(task);
		return task.await();
	}

	private void requireWorker(String operation, DlmsComponent parent) {
		if (this.worker.isWorkerThread()) {
			throw new IllegalStateException("Cannot queue DLMS " + operation + " task from the DLMS worker thread");
		}
		if (!this.worker.isRunning()) {
			throw new IllegalStateException("DLMS worker is not running for component ["
					+ (parent != null ? parent.id() : "-") + "]");
		}
	}

	public void applyTarget(DlmsComponent parent) throws Exception {
	}

	public void applyTargetAddress(int serverAddress) throws Exception {
	}
}
