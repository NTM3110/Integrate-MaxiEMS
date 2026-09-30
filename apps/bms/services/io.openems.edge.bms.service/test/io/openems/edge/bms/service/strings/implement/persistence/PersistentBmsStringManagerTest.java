package io.openems.edge.bms.service.strings.implement.persistence;

import io.openems.edge.bms.service.strings.contracts.StringRepository;

import io.openems.edge.bms.service.strings.model.PersistentStringModels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import io.openems.edge.bms.service.strings.implement.lifecycle.BmsStringLifecycle;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;

public class PersistentBmsStringManagerTest {

	@Test
	public void createDeletesRuntimeComponentsWhenPersistenceFails() throws Exception {
		var lifecycle = new RecordingLifecycle();
		var repository = new RecordingRepository();
		repository.failSynchronize = true;
		var manager = manager(lifecycle, repository);

		assertThrows(SQLException.class, () -> manager.create(definition("String 1", 1.85)));

		assertEquals(1, lifecycle.deleteCount);
		assertEquals(null, lifecycle.current);
	}

	@Test
	public void updateRestoresPreviousRuntimeDefinitionWhenPersistenceFails() throws Exception {
		var lifecycle = new RecordingLifecycle();
		lifecycle.current = definition("Before", 1.85);
		var repository = new RecordingRepository();
		repository.failSynchronize = true;
		var manager = manager(lifecycle, repository);

		assertThrows(SQLException.class, () -> manager.update(definition("After", 1.90)));

		assertEquals(2, lifecycle.updateCount);
		assertEquals("Before", lifecycle.current.stringName());
		assertEquals(1.85, lifecycle.current.cutoffVoltage(), 0.0001);
	}

	@Test
	public void ordinaryReadsDoNotWritePostgresql() throws Exception {
		var lifecycle = new RecordingLifecycle();
		lifecycle.current = definition("String 1", 1.85);
		var repository = new RecordingRepository();
		var manager = manager(lifecycle, repository);

		manager.list();
		manager.get(1);
		assertEquals(0, lifecycle.reconcileCount);
		assertEquals(0, repository.synchronizeCount);

		manager.reconcile();
		assertEquals(1, lifecycle.reconcileCount);
		assertEquals(1, repository.synchronizeCount);
	}

	@Test
	public void activationRepairsRuntimeBeforeSynchronizingMetadata() throws Exception {
		var events = new ArrayList<String>();
		var lifecycle = new RecordingLifecycle(events);
		lifecycle.current = definition("String 1", 1.85);
		var repository = new RecordingRepository(events);
		var manager = manager(lifecycle, repository);

		manager.activate();

		assertEquals(List.of("repair:1", "synchronize:1"), events);
	}

	@Test
	public void reconcileAttemptsEveryRuntimeRepairAndMetadataSynchronizationBeforeReportingFailures() throws Exception {
		var events = new ArrayList<String>();
		var lifecycle = new RecordingLifecycle(events);
		lifecycle.definitions = List.of(definition(1, "String 1", 1.85), definition(2, "String 2", 1.80));
		lifecycle.failReconcileIndices.add(1);
		var repository = new RecordingRepository(events);
		repository.failSynchronizeIndices.add(1);
		var manager = manager(lifecycle, repository);

		var failure = assertThrows(Exception.class, manager::reconcile);

		assertEquals(List.of("repair:1", "repair:2", "synchronize:1", "synchronize:2"), events);
		assertEquals("Failed to reconcile runtime components for BMS string 1", failure.getMessage());
		assertEquals(1, failure.getSuppressed().length);
		assertEquals("Failed to reconcile PostgreSQL metadata for BMS string 1",
				failure.getSuppressed()[0].getMessage());
	}

	@Test
	public void deleteRecreatesRuntimeComponentsWhenDatabaseDeleteFails() throws Exception {
		var lifecycle = new RecordingLifecycle();
		lifecycle.current = definition("String 1", 1.85);
		var repository = new RecordingRepository();
		repository.failDelete = true;
		var manager = manager(lifecycle, repository);

		assertThrows(SQLException.class, () -> manager.delete(1));

		assertEquals(1, lifecycle.deleteCount);
		assertEquals(1, lifecycle.createCount);
		assertEquals("String 1", lifecycle.current.stringName());
	}

	private static PersistentBmsStringManager manager(RecordingLifecycle lifecycle,
			RecordingRepository repository) throws Exception {
		var manager = new PersistentBmsStringManager();
		setField(manager, "lifecycle", lifecycle);
		setField(manager, "repository", repository);
		return manager;
	}

	private static Definition definition(String name, double cutoffVoltage) {
		return definition(1, name, cutoffVoltage);
	}

	private static Definition definition(int stringIndex, String name, double cutoffVoltage) {
		return new Definition(stringIndex, name, 2, "Brand", "Model", 200, cutoffVoltage, 2.30, 2.45, 1450,
				1900, 50, 1.80, 2.50, "modbus0", 1);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class RecordingLifecycle extends BmsStringLifecycle {
		private final List<String> events;
		private Definition current;
		private List<Definition> definitions;
		private int createCount;
		private int updateCount;
		private int deleteCount;
		private int reconcileCount;
		private final Set<Integer> failReconcileIndices = new HashSet<>();

		private RecordingLifecycle() {
			this(new ArrayList<>());
		}

		private RecordingLifecycle(List<String> events) {
			this.events = events;
		}

		@Override
		public synchronized Definition create(Definition request) {
			this.createCount++;
			this.current = request;
			return request;
		}

		@Override
		public synchronized Definition update(Definition request) {
			this.updateCount++;
			this.current = request;
			return request;
		}

		@Override
		public Definition get(int stringIndex) {
			return this.current;
		}

		@Override
		public List<Definition> list() {
			if (this.definitions != null) {
				return this.definitions;
			}
			return this.current == null ? List.of() : List.of(this.current);
		}

		@Override
		public synchronized void reconcileCalculator(Definition definition) throws Exception {
			this.reconcileCount++;
			this.events.add("repair:" + definition.stringIndex());
			if (this.failReconcileIndices.contains(definition.stringIndex())) {
				throw new Exception("runtime repair unavailable");
			}
		}

		@Override
		public synchronized void delete(int stringIndex) {
			this.deleteCount++;
			this.current = null;
		}
	}

	private static final class RecordingRepository implements StringRepository {
		private final List<String> events;
		private boolean failSynchronize;
		private boolean failDelete;
		private int synchronizeCount;
		private final Set<Integer> failSynchronizeIndices = new HashSet<>();

		private RecordingRepository() {
			this(new ArrayList<>());
		}

		private RecordingRepository(List<String> events) {
			this.events = events;
		}

		@Override
		public List<PersistentStringModels.Definition> list() {
			return List.of();
		}

		@Override
		public Optional<PersistentStringModels.Definition> findByIndex(int stringIndex) {
			return Optional.empty();
		}

		@Override
		public PersistentStringModels.Definition synchronize(Definition definition) throws SQLException {
			this.synchronizeCount++;
			this.events.add("synchronize:" + definition.stringIndex());
			if (this.failSynchronize || this.failSynchronizeIndices.contains(definition.stringIndex())) {
				throw new SQLException("database unavailable");
			}
			return new PersistentStringModels.Definition(UUID.randomUUID(), definition.stringIndex(), definition.id(),
					definition.stringName(), definition.cellQty(), definition.cellBrand(), definition.cellModel(),
					definition.ratedCapacity(), definition.cutoffVoltage(), definition.floatVoltage(),
					definition.boostVoltage(), definition.rNew(), definition.highResistanceThreshold(),
					definition.highTemperatureThreshold(), definition.lowVoltageThreshold(),
					definition.highVoltageThreshold(), definition.bridgeId(), definition.startTaUnitId());
		}

		@Override
		public void delete(PersistentStringModels.Definition definition) throws SQLException {
			if (this.failDelete) throw new SQLException("database delete unavailable");
		}
	}
}
