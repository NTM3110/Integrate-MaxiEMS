package io.openems.edge.bms.service.strings.implement.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

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

	private static Definition definition() {
		return new Definition(1, "String 1", 2, "Brand", "Model", 200, 1.85, 2.30, 2.45, 1450,
				1900, 50, 1.80, 2.50, "modbus0", 1);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class RecordingManager implements BmsStringManager {
		private final Definition definition;
		private int getCount;
		private int reconcileCount;

		private RecordingManager(Definition definition) {
			this.definition = definition;
		}

		@Override
		public Definition create(Definition request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Definition update(Definition request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Definition> list() {
			return List.of(this.definition);
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
