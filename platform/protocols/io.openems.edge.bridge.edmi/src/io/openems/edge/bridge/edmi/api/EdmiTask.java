package io.openems.edge.bridge.edmi.api;

import io.openems.edge.bridge.edmi.EdmiBridge;
import io.openems.edge.common.taskmanager.Priority;

/**
 * Defines an EDMI Task.
 */
public interface EdmiTask {

	/**
	 * Gets the Priority of this Task.
	 * 
	 * @return the {@link Priority}
	 */
	Priority getPriority();

	/**
	 * Gets the next time this task should run.
	 * 
	 * @return the next run time in milliseconds
	 */
	long getNextRunTime();

	/**
	 * Executes the Task.
	 * 
	 * @param bridge the {@link EdmiBridge} to use for communication
	 * @throws Exception on error
	 */
	void execute() throws Exception;


	/**
	 * Sets the next time this task should run.
	 * 
	 * @param nextRunTime the next run time in milliseconds
	 */ void setNextRunTime(long nextRunTime);
}
