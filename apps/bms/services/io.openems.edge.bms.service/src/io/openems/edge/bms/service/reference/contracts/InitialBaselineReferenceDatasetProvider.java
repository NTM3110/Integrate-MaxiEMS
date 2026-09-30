package io.openems.edge.bms.service.reference.contracts;

import io.openems.edge.bms.service.calibration.model.InitialBaselineReferenceDataset;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Provides the approved Initial Baseline currently selected for a BMS string. */
public interface InitialBaselineReferenceDatasetProvider {

	Snapshot snapshot();

	default Optional<InitialBaselineReferenceDataset> activeByStringId(UUID stringId) {
		return Optional.ofNullable(this.snapshot().byStringId().get(stringId));
	}

	default Optional<InitialBaselineReferenceDataset> activeByStringKey(String stringKey) {
		return Optional.ofNullable(this.snapshot().byStringKey().get(stringKey));
	}

	record Snapshot(Map<UUID, InitialBaselineReferenceDataset> byStringId,
			Map<String, InitialBaselineReferenceDataset> byStringKey, Instant lastRefreshAttemptAt,
			Instant lastSuccessfulRefreshAt, String lastError) {
		public Snapshot {
			byStringId = Map.copyOf(byStringId);
			byStringKey = Map.copyOf(byStringKey);
		}

		public static Snapshot empty() {
			return new Snapshot(Map.of(), Map.of(), null, null, null);
		}

		public boolean healthy() {
			return this.lastSuccessfulRefreshAt != null && this.lastError == null;
		}
	}
}
