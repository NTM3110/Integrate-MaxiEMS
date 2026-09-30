package io.openems.edge.bms.service.monitoring.model;

import io.openems.edge.bms.service.monitoring.model.ActiveCalibrationContext;
import io.openems.edge.bms.service.monitoring.model.BmsCurrentDirectionMapping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.CurrentQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.Alarm;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeEvidence;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeQuality;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.ModeSource;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot.OperatingMode;

public class BmsMonitoringSnapshotTest {

	@Test
	public void alarmBitsAreStableAndFrameCopiesCollections() {
		assertEquals(1, Alarm.LOW_CELL_VOLTAGE.mask());
		assertEquals(1 << 11, Alarm.SENSOR_MODULE_FAILURE.mask());

		var cells = new LinkedHashMap<Integer, BmsMonitoringSnapshot.CellMeasurements>();
		cells.put(1, new BmsMonitoringSnapshot.CellMeasurements(1, 2.3, 1_800.0, 25.0, 100.0, 95.0, null));
		var alarms = new LinkedHashSet<Alarm>();
		alarms.add(Alarm.LOW_SOC);
		var details = new ArrayList<BmsMonitoringSnapshot.AlarmDetail>();
		var generation = UUID.randomUUID();
		var frame = new BmsMonitoringSnapshot("str1", generation, 1, Instant.now(), 1L,
				CurrentQuality.FRESH, false, null, null, cells, null, ActiveCalibrationContext.none(),
				ModeEvidence.unknown(generation, 1, Instant.now(), "No resolver"), BmsCurrentDirectionMapping.NEGATIVE, alarms,
				Alarm.LOW_SOC.mask(), details, null);

		cells.clear();
		alarms.clear();
		assertEquals(1, frame.cells().size());
		assertEquals(List.of(Alarm.LOW_SOC), frame.activeAlarms().stream().toList());
		assertThrows(UnsupportedOperationException.class, () -> frame.cells().clear());
		assertThrows(UnsupportedOperationException.class, () -> frame.alarmEvaluations().clear());
		var publication = frame.withPublication(UUID.randomUUID(), 5);
		assertEquals(5, publication.publicationRevision());
		assertEquals(frame.sourceGeneration(), publication.sourceGeneration());
		assertEquals(frame.sourceSequence(), publication.sourceSequence());
		assertEquals(frame.capturedAt(), publication.capturedAt());
	}

	@Test
	public void alarmMaskMappingIsCompleteAndUnique() {
		int mask = 0;
		for (var alarm : Alarm.values()) {
			assertEquals(0, mask & alarm.mask());
			mask |= alarm.mask();
		}
		assertEquals((1 << Alarm.values().length) - 1, mask);
	}

	@Test
	public void unavailableMeasurementsAndModeEvidenceRemainExplicit() {
		var generation = UUID.randomUUID();
		var mode = new ModeEvidence(OperatingMode.FLOAT, ModeSource.ACTIVE_CALIBRATION, Instant.now(), generation,
				15, ModeQuality.CANDIDATE, "Waiting for stable telemetry");
		var string = new BmsMonitoringSnapshot.StringMeasurements(null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null);
		var frame = new BmsMonitoringSnapshot("str1", generation, 15, Instant.now(), null, CurrentQuality.MISSING,
				true, null, string, new LinkedHashMap<>(), null, ActiveCalibrationContext.none(), mode,
				BmsCurrentDirectionMapping.NEGATIVE, new LinkedHashSet<>(), 0, new ArrayList<>(), "Unavailable");

		assertEquals(null, frame.string().batteryVoltageV());
		assertEquals(OperatingMode.FLOAT, frame.operatingMode().mode());
		assertEquals(ModeQuality.CANDIDATE, frame.operatingMode().quality());
		assertEquals(generation, frame.operatingMode().snapshotGeneration());
		assertEquals(15, frame.operatingMode().snapshotSequence());
	}
}
