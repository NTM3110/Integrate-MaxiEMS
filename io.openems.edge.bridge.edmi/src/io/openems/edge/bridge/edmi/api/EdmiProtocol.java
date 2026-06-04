package io.openems.edge.bridge.edmi.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EdmiProtocol {

	private final List<EdmiTask> tasks = new ArrayList<>();

	public EdmiProtocol(EdmiTask... tasks) {
		for (EdmiTask task : tasks) {
			this.tasks.add(task);
		}
	}

	/**
	 * Gets the Tasks of this protocol.
	 * 
	 * @return the tasks
	 */
	public List<EdmiTask> getTasks() {
		return Collections.unmodifiableList(this.tasks);
	}
}
