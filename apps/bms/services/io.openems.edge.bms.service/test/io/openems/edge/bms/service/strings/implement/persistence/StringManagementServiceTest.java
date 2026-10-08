package io.openems.edge.bms.service.strings.implement.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Test;

import io.openems.edge.bms.service.strings.contracts.BmsStringManager;
import io.openems.edge.bms.service.strings.implement.lifecycle.BmsStringDataReader;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Detail;

public class StringManagementServiceTest {

	@Test
	public void getDataReadsTelemetryWithoutTriggeringReconciliation() throws Exception {
		var definition = definition();
		var expected = new Detail(definition, false, "test", null, List.of(), null);
		var manager = new RecordingManager(definition);
		var reader = new RecordingReader(expected);
		var service = new StringManagementService();
		setField(service, "stringManager", manager);
		setField(service, "dataReader", reader);

		var actual = service.getData(1);

		assertSame(expected, actual);
		assertEquals(1, manager.getCount);
		assertEquals(0, manager.reconcileCount);
		assertEquals(1, reader.readCount);
		assertSame(definition, reader.lastDefinition);
	}

	@Test
	public void createUsesExactIndexAndCanonicalStringIdAsLegacyName() throws Exception {
		var request = definition(7, "ignored");
		var expected = new Detail(request, false, "test", null, List.of(), null);
		var manager = new RecordingManager(null);
		var reader = new RecordingReader(expected);
		var service = new StringManagementService();
		setField(service, "stringManager", manager);
		setField(service, "dataReader", reader);

		service.create(request);

		assertEquals(1, manager.createCount);
		assertEquals(7, manager.lastCreated.stringIndex());
		assertEquals("str7", manager.lastCreated.stringName());
		assertSame(manager.lastCreated, reader.lastDefinition);
	}

	@Test
	public void createRejectsNonpositiveAndDuplicateIndicesClearly() throws Exception {
		var manager = new RecordingManager(definition());
		var service = new StringManagementService();
		setField(service, "stringManager", manager);

		var nonpositive = assertThrows(IllegalArgumentException.class,
				() -> service.create(definition(0, "ignored")));
		assertTrue(nonpositive.getMessage().contains("positive index"));

		var duplicate = assertThrows(IllegalArgumentException.class,
				() -> service.create(definition(1, "ignored")));
		assertEquals("String ID str1 already exists", duplicate.getMessage());
		assertEquals(0, manager.createCount);
	}

	@Test
	public void updateKeepsStoredLegacyName() throws Exception {
		var stored = definition(1, "Legacy display name");
		var request = definition(1, "rename attempt");
		var expected = new Detail(stored, false, "test", null, List.of(), null);
		var manager = new RecordingManager(stored);
		var reader = new RecordingReader(expected);
		var service = new StringManagementService();
		setField(service, "stringManager", manager);
		setField(service, "dataReader", reader);

		service.update(request);

		assertEquals(1, manager.updateCount);
		assertEquals("Legacy display name", manager.lastUpdated.stringName());
		assertSame(manager.lastUpdated, reader.lastDefinition);
	}

	private static Definition definition() {
		return definition(1, "String 1");
	}

	private static Definition definition(int stringIndex, String stringName) {
		return new Definition(stringIndex, stringName, 2, "Brand", "Model", 200, 1.85, 2.30, 2.45, 1450,
				1900, 50, 1.80, 2.50, "modbus0", 1);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class RecordingManager implements BmsStringManager {
		private Definition definition;
		private int getCount;
		private int reconcileCount;
		private int createCount;
		private int updateCount;
		private Definition lastCreated;
		private Definition lastUpdated;

		private RecordingManager(Definition definition) {
			this.definition = definition;
		}

		@Override
		public Definition create(Definition request) {
			this.createCount++;
			this.lastCreated = request;
			this.definition = request;
			return request;
		}

		@Override
		public Definition update(Definition request) {
			this.updateCount++;
			this.lastUpdated = request;
			this.definition = request;
			return request;
		}

		@Override
		public List<Definition> list() {
			return this.definition == null ? List.of() : List.of(this.definition);
		}

		@Override
		public Definition get(int stringIndex) {
			this.getCount++;
			return this.definition;
		}

		@Override
		public void reconcile() {
			this.reconcileCount++;
		}

		@Override
		public void delete(int stringIndex) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class RecordingReader extends BmsStringDataReader {
		private final Detail detail;
		private int readCount;
		private Definition lastDefinition;

		private RecordingReader(Detail detail) {
			this.detail = detail;
		}

		@Override
		public Detail read(Definition definition) {
			this.readCount++;
			this.lastDefinition = definition;
			return this.detail;
		}
	}
}
