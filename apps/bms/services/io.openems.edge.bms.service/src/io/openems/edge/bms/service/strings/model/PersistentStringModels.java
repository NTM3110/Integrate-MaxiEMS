package io.openems.edge.bms.service.strings.model;

import java.util.UUID;

public final class PersistentStringModels {

	private PersistentStringModels() {
	}

	public record Definition(UUID id, int stringIndex, String stringKey, String displayName, int cellCount,
			String brand, String model, double capacityAh, double cutoffVoltage, double floatVoltage,
			double boostVoltage, double ratedResistanceUohm, double highResistanceThreshold,
			double highTemperatureThreshold, double lowVoltageThreshold, double highVoltageThreshold,
			String bridgeId, int startTaUnitId, SocCheckpointPolicy socCheckpointPolicy) {
		public Definition(UUID id, int stringIndex, String stringKey, String displayName, int cellCount,
				String brand, String model, double capacityAh, double cutoffVoltage, double floatVoltage,
				double boostVoltage, double ratedResistanceUohm, double highResistanceThreshold,
				double highTemperatureThreshold, double lowVoltageThreshold, double highVoltageThreshold,
				String bridgeId, int startTaUnitId) {
			this(id, stringIndex, stringKey, displayName, cellCount, brand, model, capacityAh, cutoffVoltage,
					floatVoltage, boostVoltage, ratedResistanceUohm, highResistanceThreshold,
					highTemperatureThreshold, lowVoltageThreshold, highVoltageThreshold, bridgeId, startTaUnitId,
					SocCheckpointPolicy.DISABLED);
		}
	}

}
