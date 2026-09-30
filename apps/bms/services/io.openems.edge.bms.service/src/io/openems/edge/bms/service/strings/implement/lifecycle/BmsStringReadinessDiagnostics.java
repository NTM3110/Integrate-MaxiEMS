package io.openems.edge.bms.service.strings.implement.lifecycle;

import io.openems.edge.bms.service.strings.model.BmsStringModels;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.drivers.huasu.ta.BmsHuasuTa;
import io.openems.edge.bms.drivers.huasu.tc.BmsHuasuTc;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;

/**
 * Read-only, bounded readiness measurements for lifecycle operations. This
 * service observes existing components; it never schedules a Modbus request or
 * changes a component state.
 */
@Component(name = "io.openems.edge.bms.core.strings.BmsStringReadinessDiagnostics", service = BmsStringReadinessDiagnostics.class)
public class BmsStringReadinessDiagnostics {

	private static final int MAX_TRACES = 32;

	public record Trace(String operationId, int stringIndex, long generation, String operation, int expectedCellCount,
			String bridgeId, long startedAt, Long configAdminCompletedAt, Long componentsAvailableAt,
			Long firstTaReadAt, Long firstTcReadAt, Long completeReadAt, Long calculatorSnapshotAt,
			boolean complete, List<Integer> successfulTaIds, List<Integer> pendingTaIds, List<Integer> failedTaIds,
			String tcState, int calculatorFailureCount, int observedFailureCount, int restartCount) {
	}

	@Reference
	private ComponentManager componentManager;
	private final Map<Integer, MutableTrace> traces = new LinkedHashMap<>();
	private long generation;

	/** Monitoring may publish after readiness observes the qualifying calculator source. */
	public synchronized boolean acceptsSnapshot(int stringIndex, BmsCalculationSnapshot snapshot) {
		var trace = this.traces.get(stringIndex);
		if (trace == null) return true;
		if (!trace.complete || snapshot == null || trace.acceptedSnapshotGeneration == null) return false;
		if (trace.acceptedSnapshotGeneration.equals(snapshot.generation())) {
			return snapshot.sequence() >= trace.acceptedSnapshotSequence
					&& snapshot.capturedAt() >= trace.acceptedSnapshotCapturedAt;
		}
		return snapshot.capturedAt() > trace.acceptedSnapshotCapturedAt;
	}

	public synchronized String start(String operation, BmsStringModels.Definition definition) {
		return this.start(operation, definition, Set.of());
	}

	public synchronized String start(String operation, BmsStringModels.Definition definition,
			Set<String> retainedComponentIds) {
		var startedAt = System.currentTimeMillis();
		var initialComponents = new LinkedHashMap<String, Object>();
		for (var cell = 1; cell <= definition.cellQty(); cell++) {
			this.captureInitialComponent(initialComponents, retainedComponentIds,
					"bmsHuasuTa-" + definition.id() + "-cell" + cell);
		}
		this.captureInitialComponent(initialComponents, retainedComponentIds, "bmsHuasuTc-" + definition.id());
		this.captureInitialComponent(initialComponents, retainedComponentIds, "ctrlBmsHuasuCalc-" + definition.id());
		var trace = new MutableTrace(UUID.randomUUID().toString(), definition, operation, ++this.generation, startedAt,
				initialComponents);
		this.traces.put(definition.stringIndex(), trace);
		while (this.traces.size() > MAX_TRACES) this.traces.remove(this.traces.keySet().iterator().next());
		return trace.operationId;
	}

	private void captureInitialComponent(Map<String, Object> components, Set<String> retainedComponentIds,
			String componentId) {
		if (!retainedComponentIds.contains(componentId)) this.captureComponent(components, componentId);
	}

	private void captureComponent(Map<String, Object> components, String componentId) {
		try {
			components.put(componentId, this.componentManager.getComponent(componentId));
		} catch (Exception e) {
			// A component that is already absent has no stale instance to reject.
		}
	}

	public synchronized void configAdminCompleted(int stringIndex) {
		var trace = this.traces.get(stringIndex);
		if (trace != null) trace.configAdminCompletedAt = System.currentTimeMillis();
	}

	public synchronized void failed(int stringIndex) {
		var trace = this.traces.get(stringIndex);
		if (trace != null) trace.observedFailureCount++;
	}

	/** Observes and returns the most recent trace. Calling this method is safe to poll. */
	public synchronized Trace read(int stringIndex) {
		var trace = this.traces.get(stringIndex);
		if (trace == null) return null;
		this.observe(trace);
		return trace.freeze();
	}

	private void observe(MutableTrace trace) {
		var now = System.currentTimeMillis();
		var successful = new LinkedHashSet<Integer>();
		var failed = new LinkedHashSet<Integer>();
		var availableComponents = new LinkedHashMap<String, Object>();
		var taComponentsAvailable = true;
		for (var cell = 1; cell <= trace.definition.cellQty(); cell++) {
			var unitId = trace.definition.startTaUnitId() + cell - 1;
			var componentId = "bmsHuasuTa-" + trace.definition.id() + "-cell" + cell;
			try {
				BmsHuasuTa ta = this.componentManager.getComponent(componentId);
				if (trace.isInitialComponent(componentId, ta)) {
					taComponentsAvailable = false;
					continue;
				}
				availableComponents.put(componentId, ta);
				try {
					if (ta instanceof ModbusComponent modbus && modbus.getModbusCommunicationFailed()) {
						failed.add(unitId);
					} else if (isPostOperationValue(ta.getBatteryVoltage(), trace.startedAt)
							&& isPostOperationValue(ta.getBatteryTemperature(), trace.startedAt)
							&& isPostOperationValue(ta.getBatteryInternalResistance(), trace.startedAt)) {
						successful.add(unitId);
					}
				} catch (Exception e) {
					// Read evidence is diagnostic only. A resolved replacement object is
					// still component-availability evidence when one of its channels fails.
				}
			} catch (Exception e) {
				taComponentsAvailable = false;
			}
		}
		if (!successful.isEmpty() && trace.firstTaReadAt == null) trace.firstTaReadAt = now;
		if (!failed.equals(trace.lastFailedTaIds) && !failed.isEmpty()) trace.observedFailureCount++;
		trace.lastFailedTaIds = failed;
		trace.successfulTaIds = successful;
		trace.failedTaIds = failed;

		var tcAvailable = false;
		try {
			var componentId = "bmsHuasuTc-" + trace.definition.id();
			BmsHuasuTc tc = this.componentManager.getComponent(componentId);
			if (trace.isInitialComponent(componentId, tc)) {
				trace.tcState = "PENDING";
			} else {
				tcAvailable = true;
				availableComponents.put(componentId, tc);
				try {
					if (tc instanceof ModbusComponent modbus && modbus.getModbusCommunicationFailed()) {
						trace.tcState = "FAILED";
						trace.observedFailureCount += trace.tcFailed ? 0 : 1;
						trace.tcFailed = true;
					} else if (isPostOperationValue(tc.getStringCurrent(), trace.startedAt)
							&& isPostOperationValue(tc.getAmbientTemperature(), trace.startedAt)) {
						trace.tcState = "SUCCESS";
						trace.tcFailed = false;
						if (trace.firstTcReadAt == null) trace.firstTcReadAt = now;
					} else {
						trace.tcState = "PENDING";
					}
				} catch (Exception e) {
					trace.tcState = "PENDING";
				}
			}
		} catch (Exception e) {
			trace.tcState = "PENDING";
		}

		BmsStringCalculator calculator = null;
		var calculatorAvailable = false;
		try {
			var componentId = "ctrlBmsHuasuCalc-" + trace.definition.id();
			calculator = this.componentManager.getComponent(componentId);
			if (!trace.isInitialComponent(componentId, calculator)) {
				calculatorAvailable = true;
				availableComponents.put(componentId, calculator);
			}
		} catch (Exception e) {
			// Activation is itself a measured pending phase, not an error to create.
		}

		var componentsAvailable = taComponentsAvailable && tcAvailable && calculatorAvailable;
		var componentSetChanged = componentsAvailable && !trace.hasSameAvailableComponents(availableComponents);
		var becameAvailable = componentsAvailable
				&& (!trace.componentsPreviouslyAvailable || componentSetChanged);
		if (componentsAvailable && trace.componentsAvailableAt == null) trace.componentsAvailableAt = now;
		if (trace.componentAvailabilityObserved && becameAvailable) trace.restartCount++;
		if (!componentsAvailable || becameAvailable) {
			trace.snapshotGateEstablished = false;
			trace.lastObservedCalculatorSequence = null;
		}
		trace.componentAvailabilityObserved = true;
		trace.componentsPreviouslyAvailable = componentsAvailable;
		trace.availableComponents = componentsAvailable ? Map.copyOf(availableComponents) : Map.of();

		BmsCalculationSnapshot snapshot = null;
		var snapshotReadSucceeded = false;
		if (calculatorAvailable) {
			try {
				snapshot = calculator.latestSnapshot();
				snapshotReadSucceeded = true;
				trace.calculatorFailureCount = calculator.consecutiveSnapshotFailures();
			} catch (Exception e) {
				// A resolved calculator can still be between activation and its first cycle.
			}
		}
		var establishedSnapshotGate = false;
		if (componentsAvailable && !trace.snapshotGateEstablished && snapshotReadSucceeded) {
			// The snapshot visible when all replacement objects first resolve may have
			// been published before that readiness boundary. Require the next matching
			// calculator generation. If the snapshot cannot be inspected at that
			// boundary, the first later observation establishes (but cannot pass) the
			// barrier. A mismatched snapshot does not raise the sequence threshold.
			trace.minimumCalculatorSequence = snapshot == null
					|| snapshot.configuredCellCount() != trace.definition.cellQty()
							? 0 : snapshot.sequence() + 1;
			trace.snapshotGateEstablished = true;
			establishedSnapshotGate = true;
		}
		if (componentsAvailable && trace.snapshotGateEstablished && !establishedSnapshotGate && snapshot != null
				&& trace.lastObservedCalculatorSequence != null
				&& snapshot.sequence() < trace.lastObservedCalculatorSequence.longValue()) {
			// A retained calculator resets its local sequence in @Modified. Rebase the
			// barrier on the first inspectable post-reset snapshot and require one more
			// publication, rather than waiting forever for the old higher sequence.
			trace.minimumCalculatorSequence = snapshot.sequence() + 1;
			establishedSnapshotGate = true;
		}
		if (snapshot != null && snapshot.configuredCellCount() == trace.definition.cellQty()) {
			trace.lastObservedCalculatorSequence = snapshot.sequence();
		}
		if (successful.size() == trace.definition.cellQty() && "SUCCESS".equals(trace.tcState)
				&& trace.completeReadAt == null) {
			trace.completeReadAt = now;
		}
		if (!trace.complete && componentsAvailable && trace.snapshotGateEstablished && !establishedSnapshotGate
				&& snapshot != null
				&& snapshot.configuredCellCount() == trace.definition.cellQty()
				&& snapshot.capturedAt() >= trace.startedAt
				&& snapshot.sequence() >= trace.minimumCalculatorSequence) {
			if (trace.calculatorSnapshotAt == null) trace.calculatorSnapshotAt = now;
			trace.acceptedSnapshotGeneration = snapshot.generation();
			trace.acceptedSnapshotSequence = snapshot.sequence();
			trace.acceptedSnapshotCapturedAt = snapshot.capturedAt();
			trace.complete = true;
		}
	}

	private static boolean isPostOperationValue(Value<?> value, long startedAt) {
		if (value == null || value.get() == null || value.getTimestamp() == null) {
			return false;
		}
		// The trace timestamp has millisecond precision. Requiring a strictly later
		// millisecond prevents a retained value created just before start in the same
		// clock millisecond from being accepted as post-operation evidence.
		return value.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() > startedAt;
	}

	private static final class MutableTrace {
		private final String operationId;
		private final BmsStringModels.Definition definition;
		private final String operation;
		private final long generation;
		private final Map<String, Object> initialComponents;
		private final long startedAt;
		private Long configAdminCompletedAt;
		private Long componentsAvailableAt;
		private Long firstTaReadAt;
		private Long firstTcReadAt;
		private Long completeReadAt;
		private Long calculatorSnapshotAt;
		private UUID acceptedSnapshotGeneration;
		private long acceptedSnapshotSequence;
		private long acceptedSnapshotCapturedAt;
		private boolean complete;
		private LinkedHashSet<Integer> successfulTaIds = new LinkedHashSet<>();
		private LinkedHashSet<Integer> failedTaIds = new LinkedHashSet<>();
		private LinkedHashSet<Integer> lastFailedTaIds = new LinkedHashSet<>();
		private String tcState = "PENDING";
		private boolean tcFailed;
		private int calculatorFailureCount;
		private int observedFailureCount;
		private boolean componentAvailabilityObserved;
		private boolean componentsPreviouslyAvailable;
		private Map<String, Object> availableComponents = Map.of();
		private boolean snapshotGateEstablished;
		private long minimumCalculatorSequence;
		private Long lastObservedCalculatorSequence;
		private int restartCount;

		private MutableTrace(String operationId, BmsStringModels.Definition definition, String operation, long generation,
				long startedAt, Map<String, Object> initialComponents) {
			this.operationId = operationId;
			this.definition = definition;
			this.operation = operation;
			this.generation = generation;
			this.startedAt = startedAt;
			this.initialComponents = Map.copyOf(initialComponents);
		}

		private boolean isInitialComponent(String componentId, Object component) {
			return this.initialComponents.get(componentId) == component;
		}

		private boolean hasSameAvailableComponents(Map<String, Object> current) {
			if (this.availableComponents.size() != current.size()) {
				return false;
			}
			for (var entry : current.entrySet()) {
				if (this.availableComponents.get(entry.getKey()) != entry.getValue()) {
					return false;
				}
			}
			return true;
		}

		private Trace freeze() {
			var pending = new ArrayList<Integer>();
			for (var cell = 0; cell < this.definition.cellQty(); cell++) {
				var unitId = this.definition.startTaUnitId() + cell;
				if (!this.successfulTaIds.contains(unitId) && !this.failedTaIds.contains(unitId)) pending.add(unitId);
			}
			return new Trace(this.operationId, this.definition.stringIndex(), this.generation, this.operation,
					this.definition.cellQty(), this.definition.bridgeId(), this.startedAt, this.configAdminCompletedAt,
					this.componentsAvailableAt, this.firstTaReadAt, this.firstTcReadAt, this.completeReadAt,
					this.calculatorSnapshotAt, this.complete, List.copyOf(this.successfulTaIds), List.copyOf(pending),
					List.copyOf(this.failedTaIds), this.tcState, this.calculatorFailureCount, this.observedFailureCount,
					this.restartCount);
		}
	}
}
