package io.openems.edge.bms.service.strings.implement.persistence;

import io.openems.edge.bms.service.strings.contracts.StringManagementOperations;
import io.openems.edge.bms.service.strings.contracts.StringRepository;
import java.sql.SQLException;
import java.util.List;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.strings.contracts.BmsStringManager;
import io.openems.edge.bms.service.strings.contracts.StringManagementOperations.Readiness;
import io.openems.edge.bms.service.strings.implement.lifecycle.BmsStringDataReader;
import io.openems.edge.bms.service.strings.implement.lifecycle.BmsStringReadinessDiagnostics;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Detail;

/** Coordinates PostgreSQL business records and native OpenEMS TA/TC configuration. */
@Component(name = "io.openems.edge.bms.platform.strings.StringManagementService", service = { StringManagementService.class, StringManagementOperations.class })
public class StringManagementService implements StringManagementOperations {

	@Reference
	private BmsStringManager stringManager;
	@Reference
	private StringRepository repository;
	@Reference
	private BmsStringDataReader dataReader;
	@Reference
	private BmsStringReadinessDiagnostics readinessDiagnostics;

	public synchronized Detail create(Definition request) throws Exception {
		if (request.stringIndex() < 1) {
			throw new IllegalArgumentException("String ID must use a positive index, for example str1");
		}
		if (this.stringManager.list().stream().anyMatch(value -> value.stringIndex() == request.stringIndex())) {
			throw new IllegalArgumentException("String ID str" + request.stringIndex() + " already exists");
		}
		var canonical = withStringName(request, "str" + request.stringIndex());
		validate(canonical);
		return this.dataReader.read(this.stringManager.create(canonical));
	}

	@Override
	public synchronized Detail update(Definition request) throws Exception {
		if (request.stringIndex() < 1) {
			throw new IllegalArgumentException("String index is required for update");
		}
		var previous = this.stringManager.get(request.stringIndex());
		var definition = withStringName(request, previous.stringName());
		validate(definition);
		return this.dataReader.read(this.stringManager.update(definition));
	}

	public List<Definition> list() throws Exception {
		return this.stringManager.list();
	}

	public Detail getData(int stringIndex) throws Exception {
		var definition = this.stringManager.get(stringIndex);
		return this.dataReader.read(definition);
	}

	@Override
	public Readiness readiness(int stringIndex) {
		var trace = this.readinessDiagnostics.read(stringIndex);
		return trace == null ? null : new Readiness(trace.operationId(), trace.stringIndex(), trace.generation(),
				trace.operation(), trace.expectedCellCount(), trace.bridgeId(), trace.startedAt(),
				trace.configAdminCompletedAt(), trace.componentsAvailableAt(), trace.firstTaReadAt(), trace.firstTcReadAt(),
				trace.completeReadAt(), trace.calculatorSnapshotAt(), trace.complete(), trace.successfulTaIds(),
				trace.pendingTaIds(), trace.failedTaIds(), trace.tcState(), trace.calculatorFailureCount(),
				trace.observedFailureCount(), trace.restartCount());
	}

	/** Explicitly repairs legacy/shadow PostgreSQL rows from core ConfigAdmin. */
	public void reconcile() throws SQLException {
		try {
			this.stringManager.reconcile();
		} catch (SQLException e) {
			throw e;
		} catch (Exception e) {
			throw new SQLException("Failed to synchronize runtime BMS strings", e);
		}
	}

	/** Established persistent definitions used when creating immutable calibration events. */
	public List<io.openems.edge.bms.service.strings.model.PersistentStringModels.Definition> persistentDefinitions()
			throws SQLException {
		return this.repository.list();
	}

	public synchronized void delete(int stringIndex) throws Exception {
		this.stringManager.delete(stringIndex);
	}

	private static void validate(Definition request) {
		if (request.stringName() == null || request.stringName().isBlank()) throw new IllegalArgumentException("String name is required");
		if (request.cellQty() < 1 || request.cellQty() > 200) throw new IllegalArgumentException("Cell quantity must be between 1 and 200");
		if (request.bridgeId() == null || request.bridgeId().isBlank()) throw new IllegalArgumentException("A dedicated Modbus bridge is required");
		if (request.startTaUnitId() < 1) throw new IllegalArgumentException("Starting TA Modbus unit must be positive");
	}

	private static Definition withStringName(Definition request, String stringName) {
		return new Definition(request.stringIndex(), stringName, request.cellQty(), request.cellBrand(),
				request.cellModel(), request.ratedCapacity(), request.cutoffVoltage(), request.floatVoltage(),
				request.boostVoltage(), request.rNew(), request.highResistanceThreshold(),
				request.highTemperatureThreshold(), request.lowVoltageThreshold(), request.highVoltageThreshold(),
				request.bridgeId(), request.startTaUnitId(), request.socCheckpointPolicy());
	}
}
