package io.openems.edge.bms.service.calculation.contracts;

import java.util.Optional;

import io.openems.edge.bms.service.calculation.model.SocEstimatorCheckpoint;

/**
 * Non-blocking calculator boundary for loading and publishing restart state.
 * Implementations perform persistence outside the OpenEMS scheduler thread.
 */
public interface SocEstimatorCheckpointStore {

	Optional<SocEstimatorCheckpoint> latest(String stringKey);

	void publish(SocEstimatorCheckpoint checkpoint);

	void clear(String stringKey);
}
