package io.openems.edge.bridge.dlms.api.task;

import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bridge.dlms.api.AbstractDlmsBridge;
import io.openems.edge.bridge.dlms.api.element.DlmsElement;
import io.openems.edge.common.taskmanager.Priority;

/**
 * A DLMS ReadTask.
 */
public class DlmsReadTask extends AbstractDlmsTask {

	private final Logger log = LoggerFactory.getLogger(DlmsReadTask.class);

	public DlmsReadTask(Priority priority, DlmsElement... elements) {
		super(priority, elements);
	}

	@Override
	public ExecuteState execute(AbstractDlmsBridge bridge) {
		var parent = this.getParent();
		try {
			bridge.applyTarget(parent);
			for (var element : this.getElements()) {
				var value = bridge.read(element.getObis(), element.getAttributeIndex());
				element._setNextValue(value);
			}
			return ExecuteState.OK;
		} catch (Exception e) {
			this.log.error("[DlmsReadTask] Read failed for component [{}]: {}",
					parent != null ? parent.id() : "unknown", e.getMessage(), e);
			return new ExecuteState.Error(e);
		}
	}
}
