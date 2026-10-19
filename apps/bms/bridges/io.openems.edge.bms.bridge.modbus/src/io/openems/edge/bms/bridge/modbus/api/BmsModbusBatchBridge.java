package io.openems.edge.bms.bridge.modbus.api;

import java.util.Map;

import io.openems.edge.bridge.modbus.api.BridgeModbusSerial;

/** A serial Modbus bridge that publishes only completed BMS acquisition batches. */
public interface BmsModbusBatchBridge extends BridgeModbusSerial {

	public enum Outcome {
		SUCCESS, FAILED, SKIPPED
	}

	/** Identifies a batch across bridge restarts. */
	public record BatchKey(String generation, long batchId) {
	}

	/** Immutable result of one full pass over the protocols registered at its start. */
	public record CompletedBatch(String generation, long batchId, long startedAt, long completedAt,
			Map<String, Outcome> outcomes) {
		public CompletedBatch {
			outcomes = Map.copyOf(outcomes);
		}

		public BatchKey key() {
			return new BatchKey(this.generation, this.batchId);
		}
	}

	/**
	 * Gets the last batch made visible after an OpenEMS process-image switch.
	 *
	 * @return completed batch, or null until the first batch is published
	 */
	public CompletedBatch getCompletedBatch();

	/**
	 * Acknowledges that the calculator has handled the visible batch, including
	 * batches with no usable measurements. A stale or unknown key is ignored.
	 *
	 * @param generation bridge generation
	 * @param batchId batch number in that generation
	 * @return true if the visible batch was acknowledged
	 */
	public boolean acknowledgeProcessed(String generation, long batchId);
}
