package io.openems.edge.bms.service.calibration.implement;

import io.openems.edge.bms.service.calibration.implement.persistence.CalibrationStoreTestAccess;

import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration;
import io.openems.edge.bms.service.calibration.model.CalibrationEventType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import io.openems.edge.bms.service.calculation.model.CapacityTemperatureFactor;
import io.openems.edge.bms.service.calibration.implement.CalibrationCalculations.FloatCurrentResult;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeEndpointMode;
import io.openems.edge.bms.service.calibration.model.CalibrationEventConfiguration.DischargeMethod;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;

public class CalibrationConfigurationAndCalculationTest {

	@Test
	public void validatesPartialTargetAndRequiredVoltageFloorPolicy() {
		assertThrows(IllegalArgumentException.class,
				() -> new CalibrationEventConfiguration(CalibrationEventType.PERIODIC_DISCHARGE, "str1", 3, 100, 100,
						90.0, 1.85, 2.30, 2.45, DischargeCurrentDirection.POSITIVE, 1, -3.3, -5.0, 0.2,
						300, 60, DischargeMethod.PERIODIC, DischargeEndpointMode.PARTIAL_DOD, 34.9, 0.1, 0.2,
						0.3, "floor-v1"));
		assertThrows(IllegalArgumentException.class,
				() -> new CalibrationEventConfiguration(CalibrationEventType.PERIODIC_DISCHARGE, "str1", 3, 100, 100,
						90.0, 1.85, 2.30, 2.45, DischargeCurrentDirection.POSITIVE, 1, -3.3, -5.0, 0.2,
						300, 60, DischargeMethod.PERIODIC, DischargeEndpointMode.PARTIAL_DOD, 50.0, 0.1, 0.2,
						0.3, ""));
		assertThrows(IllegalArgumentException.class,
				() -> new CalibrationEventConfiguration(CalibrationEventType.PERIODIC_DISCHARGE, "str1", 3, 100, 100,
						90.0, 1.85, 2.30, 2.45, DischargeCurrentDirection.POSITIVE, 1, -3.3, -5.0, 0.2,
						300, 60, DischargeMethod.C10, DischargeEndpointMode.FULL_CUTOFF, null, 0.1, 0.2,
						0.3, "floor-v1"));
	}

	@Test
	public void evaluatesStrictFloatBoundariesFromActualCurrent() {
		assertEquals(FloatCurrentResult.PASS,
				CalibrationCalculations.evaluateFloatCurrent(0.099, 0.100, 0.300));
		assertEquals(FloatCurrentResult.FAIL,
				CalibrationCalculations.evaluateFloatCurrent(0.100, 0.100, 0.300));
		assertEquals(FloatCurrentResult.FAIL,
				CalibrationCalculations.evaluateFloatCurrent(0.101, 0.100, 0.300));
		assertEquals(FloatCurrentResult.PASS,
				CalibrationCalculations.evaluateFloatCurrent(0.199, 0.200, 0.300));
		assertEquals(FloatCurrentResult.FAIL,
				CalibrationCalculations.evaluateFloatCurrent(0.200, 0.200, 0.300));
		assertEquals(FloatCurrentResult.OUT_OF_RANGE,
				CalibrationCalculations.evaluateFloatCurrent(0.301, 0.200, 0.300));
		assertEquals(FloatCurrentResult.PASS,
				CalibrationCalculations.evaluateFloatCurrent(0.000, 0.100, 0.300));
	}

	@Test
	public void newConfigurationDefaultsToZeroThroughPointOneAmpereFloatWindow() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		assertEquals(0.0, configuration.floatCurrentMinA(), 0.000001);
		assertEquals(0.1, configuration.floatCurrentMaxA(), 0.000001);
	}

	@Test
	public void defaultsAndValidatesDischargeSafetyFloor() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		assertEquals(1.70, configuration.safetyFloorVoltagePerCell(), 0.000001);
		assertEquals(1.65, configuration.withSafetyFloorVoltagePerCell(1.65).safetyFloorVoltagePerCell(), 0.000001);
		assertThrows(IllegalArgumentException.class,
				() -> configuration.withSafetyFloorVoltagePerCell(configuration.cutoffVoltagePerCell()));
		assertThrows(IllegalArgumentException.class,
				() -> configuration.withSafetyFloorVoltagePerCell(Double.NaN));
		assertThrows(IllegalArgumentException.class,
				() -> configuration.withSafetyFloorVoltagePerCell(0));
	}

	@Test
	public void initialBaselineCutoffDependsOnDischargeDuration() {
		assertEquals(1.80,
				CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(DischargeMethod.C10), 0.000001);
		assertEquals(1.75,
				CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(DischargeMethod.C5), 0.000001);
		assertThrows(IllegalArgumentException.class,
				() -> CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(DischargeMethod.PERIODIC));
		assertThrows(NullPointerException.class,
				() -> CalibrationEventConfiguration.initialBaselineCutoffVoltagePerCell(null));
	}

	@Test
	public void persistedConfigurationWithoutSafetyFloorUsesLegacyDefault() {
		var original = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var json = JsonParser.parseString(new Gson().toJson(original)).getAsJsonObject();
		json.remove("safetyFloorVoltagePerCell");
		var restored = CalibrationStoreTestAccess.configuration(json.toString());
		assertEquals(1.70, restored.safetyFloorVoltagePerCell(), 0.000001);
	}

	@Test
	public void persistedInitialBaselineKeepsItsImmutableLegacyCutoff() {
		var json = JsonParser.parseString(
				new Gson().toJson(CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE)))
				.getAsJsonObject();
		json.addProperty("cutoffVoltagePerCell", 1.85);
		json.addProperty("safetyFloorVoltagePerCell", 1.75);

		var restored = CalibrationStoreTestAccess.configuration(json.toString());

		assertEquals(1.85, restored.cutoffVoltagePerCell(), 0.000001);
		assertEquals(1.75, restored.safetyFloorVoltagePerCell(), 0.000001);
	}

	@Test
	public void calculatesTemperatureTargetsAndDoesNotExtrapolateKc() {
		var configuration = CalibrationTestFixtures.initial(DischargeCurrentDirection.POSITIVE);
		var targets = CalibrationCalculations.temperatureTargets(configuration, 30.0);
		assertEquals(2.2835, targets.floatCellVoltageV(), 0.000001);
		assertEquals(2.425, targets.boostCellVoltageV(), 0.000001);
		assertEquals(1.000, CapacityTemperatureFactor.factor(25.0), 0.000001);
		assertNull(CapacityTemperatureFactor.factor(4.99));
		assertNull(CapacityTemperatureFactor.factor(45.01));
	}
}
