package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;

/** Shared pure calculations used by focused stage monitors. */
public final class CalibrationCalculations {

	private CalibrationCalculations() {
	}

	public static TemperatureTargets temperatureTargets(CalibrationEventConfiguration configuration,
			double averageCellTemperatureC) {
		double deltaTemperature = averageCellTemperatureC - CalibrationEventConfiguration.REFERENCE_TEMPERATURE_C;
		double floatCell = configuration.floatVoltagePerCell()
				+ configuration.floatTemperatureCoefficientMvPerCellC() / 1000.0 * deltaTemperature;
		double boostCell = configuration.boostVoltagePerCell()
				+ configuration.boostTemperatureCoefficientMvPerCellC() / 1000.0 * deltaTemperature;
		return new TemperatureTargets(averageCellTemperatureC, floatCell, floatCell * configuration.cellCount(),
				boostCell, boostCell * configuration.cellCount());
	}

	public static FloatCurrentResult evaluateFloatCurrent(double maxMagnitudeA, double strictLimitA,
			double overcurrentReferenceA) {
		if (!Double.isFinite(maxMagnitudeA) || maxMagnitudeA < 0) {
			return FloatCurrentResult.DATA_INVALID;
		}
		if (maxMagnitudeA > overcurrentReferenceA) {
			return FloatCurrentResult.OUT_OF_RANGE;
		}
		if (maxMagnitudeA >= strictLimitA) {
			return FloatCurrentResult.FAIL;
		}
		return FloatCurrentResult.PASS;
	}

	public record TemperatureTargets(double averageCellTemperatureC, double floatCellVoltageV,
			double floatStringVoltageV, double boostCellVoltageV, double boostStringVoltageV) {
	}

	public enum FloatCurrentResult {
		PASS,
		FAIL,
		OUT_OF_RANGE,
		DATA_INVALID
	}
}
