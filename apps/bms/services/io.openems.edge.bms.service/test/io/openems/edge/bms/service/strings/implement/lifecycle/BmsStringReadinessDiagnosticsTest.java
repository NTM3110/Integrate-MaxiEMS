package io.openems.edge.bms.service.strings.implement.lifecycle;

import io.openems.edge.bms.service.strings.model.BmsStringModels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.drivers.huasu.ta.BmsHuasuTa;
import io.openems.edge.bms.drivers.huasu.tc.BmsHuasuTc;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;

public class BmsStringReadinessDiagnosticsTest {

	@Test
	public void partialTaCoverageRequiresFollowingMatchingSnapshot() throws Exception {
		var fixture = new Fixture(110);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());

		for (var cell = 1; cell <= 110; cell++) {
			fixture.replaceTa(cell, cell <= 99 ? freshTa() : pendingTa());
		}
		fixture.replaceTc(freshTc());
		fixture.replaceCalculator(snapshot(7, initial.startedAt() + 1, 110));

		var qualifyingObservation = fixture.read();
		assertEquals(99, qualifyingObservation.successfulTaIds().size());
		assertEquals(11, qualifyingObservation.pendingTaIds().size());
		assertTrue(qualifyingObservation.componentsAvailableAt() != null);
		assertNull(qualifyingObservation.completeReadAt());
		assertFalse(qualifyingObservation.complete());
		assertFalse(fixture.read().complete());

		fixture.snapshot[0] = snapshot(8, System.currentTimeMillis(), 110);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void zeroSuccessfulTaCanExposeFollowingWarningOnlySnapshot() throws Exception {
		var fixture = new Fixture(110);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());

		for (var cell = 1; cell <= 110; cell++) {
			fixture.replaceTa(cell, pendingTa());
		}
		fixture.replaceTc(freshTc());
		fixture.replaceCalculator(snapshot(1, System.currentTimeMillis(), 110));

		var pending = fixture.read();
		assertTrue(pending.successfulTaIds().isEmpty());
		assertNull(pending.completeReadAt());
		fixture.snapshot[0] = snapshot(2, System.currentTimeMillis(), 110);
		assertTrue(fixture.read().complete());
		assertNull(fixture.read().completeReadAt());
	}

	@Test
	public void staleEvidenceAndInitialCalculatorCannotQualify() throws Exception {
		var staleTa = freshTa();
		var staleTc = freshTc();
		Thread.sleep(2);
		var fixture = new Fixture(1);
		var initial = fixture.start();

		fixture.replaceTa(1, staleTa);
		fixture.replaceTc(staleTc);
		var staleValues = fixture.read();
		assertTrue(staleValues.successfulTaIds().isEmpty());
		assertEquals("PENDING", staleValues.tcState());

		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, freshTa());
		fixture.replaceTc(freshTc());
		var initialCalculator = fixture.read();
		assertEquals(1, initialCalculator.successfulTaIds().size());
		assertEquals("SUCCESS", initialCalculator.tcState());
		assertTrue(initialCalculator.completeReadAt() != null);

		fixture.replaceCalculator(snapshot(3, initial.startedAt() - 1, 1));
		var sequenceBarrier = fixture.read();
		assertTrue(sequenceBarrier.componentsAvailableAt() != null);
		assertTrue(sequenceBarrier.completeReadAt() != null);
		assertFalse(sequenceBarrier.complete());
		fixture.snapshot[0] = snapshot(4, initial.startedAt() - 1, 1);
		assertFalse(fixture.read().complete());

		fixture.snapshot[0] = snapshot(5, System.currentTimeMillis(), 1);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void shapeMismatchRemainsBlockedForPartialCoverage() throws Exception {
		var fixture = new Fixture(110);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, freshTa());
		fixture.replaceTc(freshTc());
		fixture.replaceCalculator(snapshot(20, System.currentTimeMillis(), 109));

		var mismatched = fixture.read();
		assertNull(mismatched.completeReadAt());
		assertFalse(mismatched.complete());
		fixture.snapshot[0] = snapshot(21, System.currentTimeMillis(), 109);
		assertFalse(fixture.read().complete());
	}

	@Test
	public void oldAndMissingReplacementComponentsRemainBlocked() throws Exception {
		var fixture = new Fixture(2);
		var initial = fixture.start();
		assertNull(initial.componentsAvailableAt());
		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, pendingTa());
		fixture.removeTa(2);
		fixture.replaceTc(pendingTc());
		fixture.replaceCalculator(snapshot(1, System.currentTimeMillis(), 2));

		assertNull(fixture.read().componentsAvailableAt());

		fixture.replaceTa(2, pendingTa());
		fixture.removeTc();
		assertNull(fixture.read().componentsAvailableAt());

		fixture.replaceTc(pendingTc());
		fixture.removeCalculator();
		assertNull(fixture.read().componentsAvailableAt());

		fixture.replaceCalculator(snapshot(2, System.currentTimeMillis(), 2));
		var ready = fixture.read();
		assertTrue(ready.componentsAvailableAt() != null);
		assertFalse(ready.complete());
		fixture.snapshot[0] = snapshot(3, System.currentTimeMillis(), 2);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void availabilityLossReestablishesSnapshotGate() throws Exception {
		var fixture = new Fixture(1);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, pendingTa());
		fixture.replaceTc(pendingTc());
		fixture.replaceCalculator(snapshot(4, System.currentTimeMillis(), 1));
		assertFalse(fixture.read().complete());

		fixture.removeTa(1);
		fixture.snapshot[0] = snapshot(5, System.currentTimeMillis(), 1);
		assertFalse(fixture.read().complete());

		fixture.replaceTa(1, pendingTa());
		assertFalse(fixture.read().complete());
		fixture.snapshot[0] = snapshot(6, System.currentTimeMillis(), 1);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void calculatorIdentityChangeReestablishesSnapshotGate() throws Exception {
		var fixture = new Fixture(1);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, pendingTa());
		fixture.replaceTc(pendingTc());
		fixture.replaceCalculator(snapshot(4, System.currentTimeMillis(), 1));
		assertFalse(fixture.read().complete());

		fixture.replaceCalculator(snapshot(0, System.currentTimeMillis(), 1));
		assertFalse(fixture.read().complete());
		fixture.snapshot[0] = snapshot(1, System.currentTimeMillis(), 1);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void channelFailuresDoNotHideReplacementObjectAvailability() throws Exception {
		var fixture = new Fixture(1);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, throwingTa());
		fixture.replaceTc(throwingTc());
		fixture.replaceCalculator(snapshot(1, System.currentTimeMillis(), 1));

		var ready = fixture.read();
		assertTrue(ready.componentsAvailableAt() != null);
		assertTrue(ready.successfulTaIds().isEmpty());
		assertEquals("PENDING", ready.tcState());
		assertNull(ready.completeReadAt());
		assertFalse(ready.complete());

		fixture.snapshot[0] = snapshot(2, System.currentTimeMillis(), 1);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void failedSnapshotInspectionEstablishesBarrierOnLaterObservation() throws Exception {
		var fixture = new Fixture(1);
		var initial = fixture.start();
		awaitAfter(initial.startedAt());
		fixture.replaceTa(1, pendingTa());
		fixture.replaceTc(pendingTc());
		var reads = new int[1];
		var snapshots = new BmsCalculationSnapshot[] { snapshot(9, System.currentTimeMillis(), 1) };
		fixture.components.put("ctrlBmsHuasuCalc-str1", proxy(BmsStringCalculator.class,
				(method, arguments) -> {
					if ("latestSnapshot".equals(method) && reads[0]++ == 0) {
						throw new IllegalStateException("activating");
					}
					return "latestSnapshot".equals(method) ? snapshots[0] : 0;
				}));

		assertFalse(fixture.read().complete());
		// The first inspectable snapshot establishes the missing barrier and must
		// not qualify on that same observation.
		assertFalse(fixture.read().complete());
		assertFalse(fixture.read().complete());
		snapshots[0] = snapshot(10, System.currentTimeMillis(), 1);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void explicitlyRetainedComponentsCountAsAvailable() throws Exception {
		var fixture = new Fixture(1);
		fixture.snapshot[0] = snapshot(5, System.currentTimeMillis(), 1);
		fixture.components.put("ctrlBmsHuasuCalc-str1", calculator(fixture.snapshot));
		var initial = fixture.startRetained();
		assertTrue(initial.componentsAvailableAt() != null);
		assertFalse(initial.complete());

		fixture.snapshot[0] = snapshot(6, Math.max(System.currentTimeMillis(), initial.startedAt()), 1);
		assertTrue(fixture.read().complete());
	}

	@Test
	public void retainedCalculatorSequenceResetRebasesSnapshotBarrier() throws Exception {
		var fixture = new Fixture(1);
		fixture.snapshot[0] = snapshot(100, System.currentTimeMillis(), 1);
		fixture.components.put("ctrlBmsHuasuCalc-str1", calculator(fixture.snapshot));
		var initial = fixture.startRetained();
		assertFalse(initial.complete());

		fixture.snapshot[0] = null;
		assertFalse(fixture.read().complete());
		fixture.snapshot[0] = snapshot(0, System.currentTimeMillis(), 1);
		assertFalse(fixture.read().complete());
		fixture.snapshot[0] = snapshot(1, System.currentTimeMillis(), 1);
		assertTrue(fixture.read().complete());
	}

	private static final class Fixture {
		private final int cellCount;
		private final Map<String, Object> components = new LinkedHashMap<>();
		private final BmsStringReadinessDiagnostics diagnostics = new BmsStringReadinessDiagnostics();
		private final BmsCalculationSnapshot[] snapshot = new BmsCalculationSnapshot[1];

		private Fixture(int cellCount) throws Exception {
			this.cellCount = cellCount;
			for (var cell = 1; cell <= cellCount; cell++) {
				this.components.put(taId(cell), pendingTa());
			}
			this.components.put("bmsHuasuTc-str1", pendingTc());
			this.components.put("ctrlBmsHuasuCalc-str1", calculator(new BmsCalculationSnapshot[1]));
			var componentManager = proxy(ComponentManager.class, (method, arguments) -> {
				if (!"getComponent".equals(method)) return null;
				var component = this.components.get((String) arguments[0]);
				if (component == null) throw new IllegalArgumentException("Missing component " + arguments[0]);
				return component;
			});
			setField(this.diagnostics, "componentManager", componentManager);
		}

		private BmsStringReadinessDiagnostics.Trace start() {
			this.diagnostics.start("UPDATE", definition(this.cellCount));
			return this.read();
		}

		private BmsStringReadinessDiagnostics.Trace startRetained() {
			var retained = new java.util.LinkedHashSet<String>();
			for (var cell = 1; cell <= this.cellCount; cell++) retained.add(taId(cell));
			retained.add("bmsHuasuTc-str1");
			retained.add("ctrlBmsHuasuCalc-str1");
			this.diagnostics.start("UPDATE", definition(this.cellCount), Set.copyOf(retained));
			return this.read();
		}

		private BmsStringReadinessDiagnostics.Trace read() {
			return this.diagnostics.read(1);
		}

		private void replaceTa(int cell, BmsHuasuTa ta) {
			this.components.put(taId(cell), ta);
		}

		private void replaceTc(BmsHuasuTc tc) {
			this.components.put("bmsHuasuTc-str1", tc);
		}

		private void removeTa(int cell) {
			this.components.remove(taId(cell));
		}

		private void removeTc() {
			this.components.remove("bmsHuasuTc-str1");
		}

		private void removeCalculator() {
			this.components.remove("ctrlBmsHuasuCalc-str1");
		}

		private void replaceCalculator(BmsCalculationSnapshot value) {
			this.snapshot[0] = value;
			this.components.put("ctrlBmsHuasuCalc-str1", calculator(this.snapshot));
		}
	}

	private static String taId(int cell) {
		return "bmsHuasuTa-str1-cell" + cell;
	}

	private static BmsHuasuTa freshTa() {
		return ta(new Value<Integer>(null, 2200), new Value<Integer>(null, 250), new Value<Integer>(null, 100));
	}

	private static BmsHuasuTa pendingTa() {
		return ta(null, null, null);
	}

	private static BmsHuasuTa throwingTa() {
		return proxy(BmsHuasuTa.class, (method, arguments) -> {
			if (method.startsWith("getBattery") || "getModbusCommunicationFailed".equals(method)) {
				throw new IllegalStateException("channel unavailable");
			}
			return null;
		});
	}

	private static BmsHuasuTa ta(Value<Integer> voltage, Value<Integer> temperature, Value<Integer> resistance) {
		return proxy(BmsHuasuTa.class, (method, arguments) -> switch (method) {
		case "getModbusCommunicationFailed" -> false;
		case "getBatteryVoltage" -> voltage;
		case "getBatteryTemperature" -> temperature;
		case "getBatteryInternalResistance" -> resistance;
		default -> null;
		});
	}

	private static BmsHuasuTc freshTc() {
		return tc(new Value<Integer>(null, 10), new Value<Integer>(null, 250));
	}

	private static BmsHuasuTc pendingTc() {
		return tc(null, null);
	}

	private static BmsHuasuTc throwingTc() {
		return proxy(BmsHuasuTc.class, (method, arguments) -> {
			if (method.startsWith("get") && !"getComponentContext".equals(method)) {
				throw new IllegalStateException("channel unavailable");
			}
			return null;
		});
	}

	private static BmsHuasuTc tc(Value<Integer> current, Value<Integer> temperature) {
		return proxy(BmsHuasuTc.class, (method, arguments) -> switch (method) {
		case "getModbusCommunicationFailed" -> false;
		case "getStringCurrent" -> current;
		case "getAmbientTemperature" -> temperature;
		default -> null;
		});
	}

	private static BmsStringCalculator calculator(BmsCalculationSnapshot[] snapshot) {
		return proxy(BmsStringCalculator.class, (method, arguments) -> switch (method) {
		case "latestSnapshot" -> snapshot[0];
		case "consecutiveSnapshotFailures" -> 0;
		default -> null;
		});
	}

	private static BmsCalculationSnapshot snapshot(long sequence, long capturedAt, int configuredCellCount) {
		return new BmsCalculationSnapshot(UUID.randomUUID(), sequence, capturedAt, configuredCellCount,
				25d, 1d, capturedAt, BmsCalculationSnapshot.CurrentQuality.FRESH, null,
				50d, 90d, 2.2d, 2.2d, 25d, 100d,
				2.2d, 1, 2.1d, 1, 26d, 1, 24d, 1, 110, 1, 90, 1,
				Map.of(1, new BmsCalculationSnapshot.Cell(1, 2.2d, 25d, 100, 50d, 90d, 0.001d, null)), null);
	}

	private static void awaitAfter(long timestamp) throws InterruptedException {
		while (System.currentTimeMillis() <= timestamp) Thread.sleep(1);
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
