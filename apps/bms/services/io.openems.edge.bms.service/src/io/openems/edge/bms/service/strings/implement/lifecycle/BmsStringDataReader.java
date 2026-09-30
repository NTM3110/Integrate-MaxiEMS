package io.openems.edge.bms.service.strings.implement.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot;
import io.openems.edge.bms.service.strings.model.BmsStringModels.CalculationStatus;
import io.openems.edge.bms.service.strings.model.BmsStringModels.CellData;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Detail;
import io.openems.edge.bms.service.strings.model.BmsStringModels.StringData;
import io.openems.edge.common.component.ComponentManager;

/** Compatibility telemetry from one monitoring publication; diagnostics remain separate. */
@Component(name = "io.openems.edge.bms.core.strings.BmsStringDataReader", service = BmsStringDataReader.class)
public class BmsStringDataReader {
	/** A stopped scheduler must not leave frozen live values visible indefinitely. */
	private static final long SNAPSHOT_FRESHNESS_TIMEOUT_MS = 60_000;

	@Reference
	private ComponentManager componentManager;
	@Reference
	private BmsStringReadinessDiagnostics readinessDiagnostics;
	private final List<BmsMonitoringSnapshotProvider> monitoringProviders = new CopyOnWriteArrayList<>();

	@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	protected void addMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.monitoringProviders.add(provider);
	}

	protected void removeMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.monitoringProviders.remove(provider);
	}

	public Detail read(Definition definition) {
		// Diagnostics may observe a newer calculator publication before monitoring
		// catches up. Check the captured monitoring source against the readiness barrier.
		var readinessTrace = this.readinessDiagnostics.read(definition.stringIndex());
		BmsStringCalculator calculator = null;
		try {
			calculator = this.componentManager.getComponent("ctrlBmsHuasuCalc-" + definition.id());
		} catch (Exception ignored) {
			// Calculator lookup is diagnostics only, never a telemetry fallback.
		}
		var providers = this.monitoringProviders.stream()
				.filter(provider -> definition.id().equals(provider.stringKey())).toList();
		var monitoring = providers.size() == 1 ? providers.get(0).latestFrame() : null;
		BmsCalculationSnapshot snapshot = monitoring == null || !definition.id().equals(monitoring.stringKey())
				? null : monitoring.calculationSnapshot();
		var readinessPending = (readinessTrace != null && !readinessTrace.complete())
				|| !this.readinessDiagnostics.acceptsSnapshot(definition.stringIndex(), snapshot);

		var snapshotCellCountMismatch = snapshot != null && snapshot.configuredCellCount() != definition.cellQty();
		var usableSnapshot = readinessPending || snapshotCellCountMismatch ? null : snapshot;
		var usableMonitoring = usableSnapshot == null ? null : monitoring;
		var cells = new ArrayList<CellData>(definition.cellQty());
		for (var cellId = 1; cellId <= definition.cellQty(); cellId++) {
			var cell = usableSnapshot == null ? null : usableSnapshot.cells().get(cellId);
			cells.add(cell == null
					? new CellData(cellId, null, null, null, null, null, null,
							readinessPending ? "READINESS_PENDING"
									: snapshotCellCountMismatch ? "CONFIGURATION_MISMATCH" : null)
					: new CellData(cell.cellId(), cell.voltage(), cell.temperature(), cell.internalResistance(),
							cell.soc(), cell.soh(), cell.ir(), cell.availabilityWarning(), cell.socEstimator(),
							cell.sohEstimator()));
		}
		var unavailableTaCellIds = cells.stream().filter(cell -> cell.availabilityWarning() != null)
				.map(CellData::cellId).toList();

		var snapshotAgeMs = snapshot == null ? null : Math.max(0, System.currentTimeMillis() - snapshot.capturedAt());
		var snapshotStale = snapshotAgeMs != null
				&& (monitoring.stale() || snapshotAgeMs >= SNAPSHOT_FRESHNESS_TIMEOUT_MS);
		if (snapshotStale) {
			for (var index = 0; index < cells.size(); index++) {
				var cell = cells.get(index);
				cells.set(index, new CellData(cell.cellId(), null, null, null, null, null, null,
						cell.availabilityWarning(), null, null));
			}
		}
		var data = usableSnapshot == null || snapshotStale ? emptyStringData() : stringData(usableSnapshot);
		var active = !snapshotStale && usableSnapshot != null
				&& cells.stream().anyMatch(cell -> cell.voltage() != null);
		var error = calculator == null
				? "Calculator component is not active"
				: snapshot == null
						? firstNonBlank(calculator.lastCalculationError(), "Waiting for the first monitoring snapshot")
						: snapshotCellCountMismatch
								? "Snapshot configuration mismatch: expected " + definition.cellQty() + " cells but received "
										+ snapshot.configuredCellCount()
								: readinessPending
										? "Waiting for post-configuration TA/TC reads and calculator snapshot"
								: calculator.lastCalculationError();
		var status = calculator == null
				? new CalculationStatus(false, 0, 0, null, null, null, error, null, null, 0, false,
						null, unavailableTaCellIds, null)
				: new CalculationStatus(true,
						usableSnapshot == null ? 0 : usableSnapshot.calculatedSocCellCount(),
						usableSnapshot == null ? 0 : usableSnapshot.calculatedSohCellCount(),
						calculator.cellCutoffVoltage(), calculator.cellFloatVoltage(), calculator.ratedResistanceUohm(),
						error, snapshot == null ? null : snapshot.sequence(), snapshot == null ? null : snapshot.capturedAt(),
						calculator.consecutiveSnapshotFailures(), snapshotStale, snapshotAgeMs, unavailableTaCellIds,
						usableSnapshot == null ? null : usableSnapshot.stringWarning());
		var message = snapshot == null ? "Waiting for a complete monitoring snapshot"
				: snapshotCellCountMismatch
						? "MISMATCHED snapshot " + snapshot.sequence() + " (expected " + definition.cellQty()
								+ " cells, received " + snapshot.configuredCellCount() + "; live values unavailable)"
				: readinessPending ? "READINESS PENDING (live values unavailable)"
				: snapshotStale ? "STALE snapshot " + snapshot.sequence() + " (live values unavailable)"
						: "ACTIVE snapshot " + snapshot.sequence() + " (" + snapshot.configuredCellCount() + " cells processed)";
		return new Detail(definition, active, message, data, List.copyOf(cells), status, usableMonitoring);
	}

	private static StringData stringData(BmsCalculationSnapshot value) {
		return new StringData(value.ambientTemperature(), value.stringCurrent(), value.stringSoc(), value.stringSoh(),
				value.stringVoltage(), value.midpointVoltage(), value.averageVoltage(), value.averageTemperature(), value.averageResistance(),
				value.maxVoltage(), value.maxVoltageCellIndex(), value.minVoltage(), value.minVoltageCellIndex(),
				value.maxTemperature(), value.maxTemperatureCellIndex(), value.minTemperature(), value.minTemperatureCellIndex(),
				value.maxResistance(), value.maxResistanceCellIndex(), value.minResistance(), value.minResistanceCellIndex(),
				value.socEstimator(), value.sohEstimator());
	}

	private static StringData emptyStringData() {
		return new StringData(null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	private static String firstNonBlank(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
