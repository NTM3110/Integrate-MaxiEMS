package io.openems.edge.bms.service.strings.implement.lifecycle;

import io.openems.edge.bms.service.strings.model.BmsStringModels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorProvenance;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot.EstimatorQuality;
import io.openems.edge.bms.drivers.huasu.ta.BmsHuasuTa;
import io.openems.edge.bms.drivers.huasu.tc.BmsHuasuTc;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;

public class BmsStringDataReaderTest {

	@Test
	public void rejectsSnapshotWithDifferentConfiguredCellCount() throws Exception {
		var snapshot = new BmsCalculationSnapshot(UUID.randomUUID(), 7, System.currentTimeMillis(), 110,
				25d, 1d, System.currentTimeMillis(), BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				50d, 90d, 238d, 2.16d, 25d, 100d,
				2.2d, 1, 2.1d, 2, 26d, 1, 24d, 2, 110, 1, 90, 2,
				Map.of(1, new BmsCalculationSnapshot.Cell(1, 2.2d, 25d, 100, 50d, 90d, 0.001d, null)), null);
		var calculator = proxy(BmsStringCalculator.class, (method, arguments) -> switch (method) {
		case "latestSnapshot" -> snapshot;
		case "cellCutoffVoltage" -> 2.5d;
		case "cellFloatVoltage" -> 3.5d;
		case "ratedResistanceUohm" -> 100;
		case "consecutiveSnapshotFailures" -> 0;
		default -> null;
		});
		var componentManager = proxy(ComponentManager.class,
				(method, arguments) -> "getComponent".equals(method) ? calculator : null);
		var reader = new BmsStringDataReader();
		reader.addMonitoringProvider(provider(() -> snapshot));
		setField(reader, "componentManager", componentManager);
		setField(reader, "readinessDiagnostics", new BmsStringReadinessDiagnostics());

		var detail = reader.read(definition(10));

		assertFalse(detail.active());
		assertTrue(detail.statusMessage().startsWith("MISMATCHED snapshot 7"));
		assertNull(detail.stringData().ambientTemperature());
		assertNull(detail.stringData().stringCurrent());
		assertNull(detail.stringData().stringSoC());
		assertNull(detail.stringData().stringSoH());
		assertNull(detail.stringData().stringVoltage());
		assertEquals(10, detail.cellsData().size());
		assertTrue(detail.cellsData().stream().allMatch(cell -> cell.voltage() == null && cell.temperature() == null
				&& cell.internalResistance() == null && cell.soc() == null && cell.soh() == null && cell.ir() == null));
		assertTrue(detail.cellsData().stream()
				.allMatch(cell -> "CONFIGURATION_MISMATCH".equals(cell.availabilityWarning())));
		assertTrue(detail.calculationStatus().error().contains("expected 10 cells but received 110"));
		assertEquals(0, detail.calculationStatus().calculatedSocCellCount());
		assertEquals(0, detail.calculationStatus().calculatedSohCellCount());
		assertFalse(detail.calculationStatus().snapshotStale());
		assertEquals(Long.valueOf(7), detail.calculationStatus().snapshotSequence());
		assertNull(detail.monitoring());
	}

	@Test
	public void hidesMatchingSnapshotWhileReadinessIsPending() throws Exception {
		var snapshot = new BmsCalculationSnapshot(UUID.randomUUID(), 1, System.currentTimeMillis(), 10,
				25d, 1d, System.currentTimeMillis(), BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				50d, 90d, 22d, 2.2d, 25d, 100d,
				2.2d, 1, 2.1d, 2, 26d, 1, 24d, 2, 110, 1, 90, 2,
				Map.of(1, new BmsCalculationSnapshot.Cell(1, 2.2d, 25d, 100, 50d, 90d, 0.001d, null)), null);
		var calculator = proxy(BmsStringCalculator.class, (method, arguments) -> switch (method) {
		case "latestSnapshot" -> snapshot;
		case "cellCutoffVoltage" -> 2.5d;
		case "cellFloatVoltage" -> 3.5d;
		case "ratedResistanceUohm" -> 100;
		case "consecutiveSnapshotFailures" -> 0;
		default -> null;
		});
		var componentManager = proxy(ComponentManager.class,
				(method, arguments) -> "getComponent".equals(method) ? calculator : null);
		var readiness = new BmsStringReadinessDiagnostics() {
			@Override
			public synchronized Trace read(int stringIndex) {
				return new Trace("operation", stringIndex, 1, "UPDATE", 10, "modbus0",
						System.currentTimeMillis(), null, null, null, null, null, null, false,
						List.of(), List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), List.of(), "PENDING", 0, 0, 0);
			}
		};
		var reader = new BmsStringDataReader();
		reader.addMonitoringProvider(provider(() -> snapshot));
		setField(reader, "componentManager", componentManager);
		setField(reader, "readinessDiagnostics", readiness);

		var detail = reader.read(definition(10));

		assertFalse(detail.active());
		assertEquals("READINESS PENDING (live values unavailable)", detail.statusMessage());
		assertNull(detail.monitoring());
		assertNull(detail.stringData().stringVoltage());
		assertTrue(detail.cellsData().stream().allMatch(cell -> cell.voltage() == null));
		assertTrue(detail.cellsData().stream()
				.allMatch(cell -> "READINESS_PENDING".equals(cell.availabilityWarning())));
	}

	@Test
	public void readsSnapshotAfterReadinessCanComplete() throws Exception {
		var snapshots = new BmsCalculationSnapshot[] { snapshotWithVoltage(1, 1d) };
		var calculator = proxy(BmsStringCalculator.class, (method, arguments) -> switch (method) {
		case "latestSnapshot" -> snapshots[0];
		case "cellCutoffVoltage" -> 2.5d;
		case "cellFloatVoltage" -> 3.5d;
		case "ratedResistanceUohm", "consecutiveSnapshotFailures" -> 0;
		case "lastCalculationError" -> null;
		default -> null;
		});
		var componentManager = proxy(ComponentManager.class,
				(method, arguments) -> "getComponent".equals(method) ? calculator : null);
		var readiness = new BmsStringReadinessDiagnostics() {
			@Override
			public synchronized Trace read(int stringIndex) {
				snapshots[0] = snapshotWithVoltage(2, 2d);
				return new Trace("operation", stringIndex, 1, "UPDATE", 1, "modbus0",
						System.currentTimeMillis(), null, null, null, null, null, null, true,
						List.of(), List.of(1), List.of(), "PENDING", 0, 0, 0);
			}
		};
		var reader = new BmsStringDataReader();
		reader.addMonitoringProvider(provider(() -> snapshots[0]));
		setField(reader, "componentManager", componentManager);
		setField(reader, "readinessDiagnostics", readiness);

		var detail = reader.read(definition(1));

		assertTrue(detail.active());
		assertEquals(Double.valueOf(2d), detail.cellsData().get(0).voltage());
		assertEquals(Double.valueOf(2d), detail.stringData().midpointVoltage());
		assertEquals(Long.valueOf(2), detail.calculationStatus().snapshotSequence());
		assertEquals(2L, detail.monitoring().sourceSequence());
	}

	@Test
	public void readinessRequiresPostOperationValuesAndMatchingSnapshot() throws Exception {
		var taValues = new Value<?>[] { new Value<Integer>(null, 2200), new Value<Integer>(null, 250),
				new Value<Integer>(null, 100) };
		var tcValues = new Value<?>[] { new Value<Integer>(null, 10), new Value<Integer>(null, 250) };
		var snapshot = new BmsCalculationSnapshot[] { snapshot(1, 2) };
		var ta = proxy(BmsHuasuTa.class, (method, arguments) -> switch (method) {
		case "getBatteryVoltage" -> taValues[0];
		case "getBatteryTemperature" -> taValues[1];
		case "getBatteryInternalResistance" -> taValues[2];
		default -> null;
		});
		var tc = proxy(BmsHuasuTc.class, (method, arguments) -> switch (method) {
		case "getStringCurrent" -> tcValues[0];
		case "getAmbientTemperature" -> tcValues[1];
		default -> null;
		});
		var calculator = proxy(BmsStringCalculator.class,
				(method, arguments) -> "latestSnapshot".equals(method) ? snapshot[0] : 0);
		var taComponent = new Object[] { ta };
		var tcComponent = new Object[] { tc };
		var calculatorComponent = new Object[] { calculator };
		var componentManager = proxy(ComponentManager.class, (method, arguments) -> switch ((String) arguments[0]) {
		case "bmsHuasuTa-str1-cell1" -> taComponent[0];
		case "bmsHuasuTc-str1" -> tcComponent[0];
		case "ctrlBmsHuasuCalc-str1" -> calculatorComponent[0];
		default -> throw new IllegalArgumentException("Unexpected component " + arguments[0]);
		});
		var diagnostics = new BmsStringReadinessDiagnostics();
		setField(diagnostics, "componentManager", componentManager);

		Thread.sleep(2);
		diagnostics.start("UPDATE", definition(1));
		var retained = diagnostics.read(1);

		assertFalse(retained.complete());
		assertEquals(List.of(1), retained.pendingTaIds());
		assertEquals("PENDING", retained.tcState());

		while (System.currentTimeMillis() <= retained.startedAt()) {
			Thread.sleep(1);
		}
		taValues[0] = new Value<Integer>(null, 2200);
		taValues[1] = new Value<Integer>(null, 250);
		taValues[2] = new Value<Integer>(null, 100);
		tcValues[0] = new Value<Integer>(null, 10);
		tcValues[1] = new Value<Integer>(null, 250);
		taComponent[0] = proxy(BmsHuasuTa.class, (method, arguments) -> switch (method) {
		case "getBatteryVoltage" -> taValues[0];
		case "getBatteryTemperature" -> taValues[1];
		case "getBatteryInternalResistance" -> taValues[2];
		default -> null;
		});
		tcComponent[0] = proxy(BmsHuasuTc.class, (method, arguments) -> switch (method) {
		case "getStringCurrent" -> tcValues[0];
		case "getAmbientTemperature" -> tcValues[1];
		default -> null;
		});
		calculatorComponent[0] = proxy(BmsStringCalculator.class, (method, arguments) -> switch (method) {
		case "latestSnapshot" -> snapshot[0];
		case "cellCutoffVoltage" -> 2.5d;
		case "cellFloatVoltage" -> 3.5d;
		case "ratedResistanceUohm", "consecutiveSnapshotFailures" -> 0;
		case "lastCalculationError" -> null;
		default -> null;
		});
		var mismatched = diagnostics.read(1);

		assertFalse(mismatched.complete());
		assertTrue(mismatched.completeReadAt() != null);
		var preBarrierSnapshot = snapshot[0];
		snapshot[0] = snapshot(0, 1, "DATA_UNAVAILABLE");
		var complete = diagnostics.read(1);

		assertTrue(complete.complete());
		assertFalse(diagnostics.acceptsSnapshot(1, preBarrierSnapshot));
		assertTrue(diagnostics.acceptsSnapshot(1, snapshot[0]));
		var monitoringSource = new BmsCalculationSnapshot[] { preBarrierSnapshot };
		var reader = new BmsStringDataReader();
		reader.addMonitoringProvider(provider(() -> monitoringSource[0]));
		setField(reader, "componentManager", componentManager);
		setField(reader, "readinessDiagnostics", diagnostics);
		assertFalse(reader.read(definition(1)).active());
		monitoringSource[0] = snapshot[0];
		var detail = reader.read(definition(1));
		assertTrue(detail.active());
		assertEquals(Double.valueOf(2.2d), detail.cellsData().get(0).voltage());
		assertEquals("DATA_UNAVAILABLE", detail.cellsData().get(0).availabilityWarning());
	}

	@Test
	public void telemetryNeverFallsBackToCalculatorAndDoesNotRequireReference() throws Exception {
		var source = snapshotWithVoltage(8, 2.3);
		var calculator = proxy(BmsStringCalculator.class, (method, arguments) -> switch (method) {
		case "latestSnapshot" -> throw new AssertionError("Telemetry must not read calculator snapshot");
		case "cellCutoffVoltage", "cellFloatVoltage" -> 2d;
		case "ratedResistanceUohm", "consecutiveSnapshotFailures" -> 0;
		default -> null;
		});
		var reader = new BmsStringDataReader();
		setField(reader, "componentManager", proxy(ComponentManager.class,
				(method, arguments) -> "getComponent".equals(method) ? calculator : null));
		setField(reader, "readinessDiagnostics", new BmsStringReadinessDiagnostics());
		assertFalse(reader.read(definition(1)).active());
		reader.addMonitoringProvider(provider(() -> source));
		var detail = reader.read(definition(1));
		assertTrue(detail.active());
		assertEquals(Double.valueOf(2.3), detail.stringData().stringVoltage());
		assertEquals(Double.valueOf(0.001), detail.cellsData().get(0).ir());
		assertEquals("VOLTAGE_FALLBACK", detail.stringData().socEstimator().method());
		assertEquals(EstimatorQuality.ESTIMATED, detail.cellsData().get(0).socEstimator().quality());
		assertEquals(Long.valueOf(8), detail.calculationStatus().snapshotSequence());
		reader.addMonitoringProvider(provider(() -> source));
		assertFalse(reader.read(definition(1)).active()); // Ambiguous owner fails closed.
	}

	private static BmsMonitoringSnapshotProvider provider(java.util.function.Supplier<BmsCalculationSnapshot> source) {
		return proxy(BmsMonitoringSnapshotProvider.class, (method, arguments) -> switch (method) {
		case "stringKey" -> "str1";
		case "latestFrame" -> {
			var snapshot = source.get();
			yield new BmsMonitoringSnapshot("str1", snapshot.generation(), snapshot.sequence(),
					java.time.Instant.ofEpochMilli(snapshot.capturedAt()), snapshot.currentMeasuredAt(),
					snapshot.currentQuality(), false, null, null, Map.of(), null, null, null, null,
					java.util.Set.of(), 0, List.of(), null, snapshot, snapshot.generation(), 1, Map.of());
		}
		default -> null;
		});
	}

	private static BmsCalculationSnapshot snapshot(long sequence, int configuredCellCount) {
		return snapshot(sequence, configuredCellCount, null);
	}

	private static BmsCalculationSnapshot snapshot(long sequence, int configuredCellCount, String availabilityWarning) {
		return new BmsCalculationSnapshot(UUID.randomUUID(), sequence, System.currentTimeMillis(), configuredCellCount,
				25d, 1d, System.currentTimeMillis(), BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				50d, 90d, 2.2d, 2.2d, 25d, 100d,
				2.2d, 1, 2.1d, 1, 26d, 1, 24d, 1, 110, 1, 90, 1,
				Map.of(1, new BmsCalculationSnapshot.Cell(1, 2.2d, 25d, 100, 50d, 90d, 0.001d,
						availabilityWarning)), null);
	}

	private static BmsCalculationSnapshot snapshotWithVoltage(long sequence, double voltage) {
		var provenance = new EstimatorProvenance("VOLTAGE_FALLBACK", "NO_REFERENCE",
				EstimatorQuality.ESTIMATED, "Complete and publish an Initial Baseline", null);
		return new BmsCalculationSnapshot(UUID.randomUUID(), sequence, System.currentTimeMillis(), 1,
				25d, 1d, System.currentTimeMillis(), BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				50d, 90d, voltage, voltage, voltage, 25d, 100d,
				voltage, 1, voltage, 1, 25d, 1, 25d, 1, 100, 1, 100, 1,
				Map.of(1, new BmsCalculationSnapshot.Cell(1, voltage, 25d, 100, 50d, 90d, 0.001d, null,
						provenance, provenance)), null, provenance, provenance);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static BmsStringModels.Definition definition(int cellQty) {
		return new BmsStringModels.Definition(1, "String 1", cellQty, "brand", "model", 100d, 2.5d, 3.5d,
				3.6d, 100d, 200d, 50d, 2d, 4d, "modbus0", 1);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, ProxyInvocation invocation) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				(proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
	}

	@FunctionalInterface
	private interface ProxyInvocation {
		Object invoke(String method, Object[] arguments) throws Throwable;
	}
}
