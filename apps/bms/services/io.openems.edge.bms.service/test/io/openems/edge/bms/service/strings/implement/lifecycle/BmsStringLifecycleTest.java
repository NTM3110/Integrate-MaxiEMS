package io.openems.edge.bms.service.strings.implement.lifecycle;

import io.openems.edge.bms.service.strings.model.BmsStringModels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.junit.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import io.openems.common.utils.ConfigUtils;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.bms.service.strings.model.SocCheckpointPolicy;

public class BmsStringLifecycleTest {

	@Test
	public void createIncludesExactTargetInFirstTaAndTcUpdate() throws Exception {
		var fixture = new Fixture();
		fixture.lifecycle.create(definition(2));

		assertEquals(5, fixture.admin.configurations.size());
		for (var configuration : fixture.admin.configurations) {
			assertEquals(1, configuration.updateCount);
			var id = String.valueOf(configuration.properties.get("id"));
			if (id.startsWith("bmsHuasu")) {
				assertEquals(ConfigUtils.generateReferenceTargetFilter(configuration.pid, "modbus0"),
						configuration.properties.get("Modbus.target"));
			} else {
				assertEquals(null, configuration.properties.get("Modbus.target"));
			}
		}
		assertEquals("str1", fixture.admin.byId("ctrlBmsHuasuCalc-str1").properties.get("stringKey"));
		assertEquals("AUTO", fixture.admin.byId("ctrlBmsHuasuCalc-str1").properties.get("estimatorMode"));
	}

	@Test
	public void updatePreservesExplicitLegacyRollbackMode() throws Exception {
		var fixture = seeded(1);
		var calculator = fixture.admin.byId("ctrlBmsHuasuCalc-str1");
		calculator.properties.put("estimatorMode", "LEGACY");
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(1));

		assertEquals("LEGACY", calculator.properties.get("estimatorMode"));
		assertEquals(0, calculator.updateCount);
	}

	@Test
	public void startupReconciliationPreservesValidEstimatorModeAndMonitoringDirection() throws Exception {
		var fixture = seeded(1);
		var calculator = fixture.admin.byId("ctrlBmsHuasuCalc-str1");
		var monitoring = fixture.admin.byId("ctrlBmsMonitoring-str1");
		calculator.properties.put("estimatorMode", "LEGACY");
		calculator.properties.put("dischargeCurrentDirection", "NEGATIVE");
		calculator.properties.put("stringKey", "wrong");
		monitoring.properties.put("dischargeCurrentDirection", "POSITIVE");
		fixture.admin.resetEvents();

		fixture.lifecycle.reconcileCalculator(definition(1));

		assertEquals("LEGACY", calculator.properties.get("estimatorMode"));
		assertEquals("POSITIVE", calculator.properties.get("dischargeCurrentDirection"));
		assertEquals("POSITIVE", monitoring.properties.get("dischargeCurrentDirection"));
		assertEquals("str1", calculator.properties.get("stringKey"));
		assertEquals(1, calculator.updateCount);
		assertEquals(0, monitoring.updateCount);
	}

	@Test
	public void startupReconciliationDefaultsMissingOrInvalidManualPolicies() throws Exception {
		var fixture = seeded(1);
		var calculator = fixture.admin.byId("ctrlBmsHuasuCalc-str1");
		var monitoring = fixture.admin.byId("ctrlBmsMonitoring-str1");
		calculator.properties.put("estimatorMode", "EXPERIMENTAL");
		calculator.properties.put("dischargeCurrentDirection", "BACKWARDS");
		monitoring.properties.remove("dischargeCurrentDirection");
		fixture.admin.resetEvents();

		fixture.lifecycle.reconcileCalculator(definition(1));

		assertEquals("AUTO", calculator.properties.get("estimatorMode"));
		assertEquals("NEGATIVE", calculator.properties.get("dischargeCurrentDirection"));
		assertEquals("NEGATIVE", monitoring.properties.get("dischargeCurrentDirection"));
		assertEquals(1, calculator.updateCount);
		assertEquals(1, monitoring.updateCount);
	}

	@Test
	public void unchangedUpdateDoesNotWriteRetainedConfigurations() throws Exception {
		var fixture = seeded(11);
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(11));

		assertEquals(0, fixture.admin.createdCount);
		assertEquals(0, fixture.admin.totalUpdates());
		assertEquals(0, fixture.admin.totalDeletes());
	}

	@Test
	public void monitoringDirectionIsPersistedAndSharedWithCalculatorDuringReconciliation() throws Exception {
		var fixture = seeded(1);
		var monitoring = fixture.admin.byId("ctrlBmsMonitoring-str1");
		assertEquals("NEGATIVE", monitoring.properties.get("dischargeCurrentDirection"));
		monitoring.properties.put("dischargeCurrentDirection", "POSITIVE");
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(1));

		assertEquals("POSITIVE", monitoring.properties.get("dischargeCurrentDirection"));
		assertEquals(0, monitoring.updateCount);
		var calculator = fixture.admin.byId("ctrlBmsHuasuCalc-str1");
		assertEquals("POSITIVE", calculator.properties.get("dischargeCurrentDirection"));
		assertEquals(1, calculator.updateCount);
	}

	@Test
	public void checkpointPolicyIsPersistedAndSharedWithCalculator() throws Exception {
		var fixture = seeded(1);
		var current = definition(1);
		var persistent = new BmsStringModels.Definition(current.stringIndex(), current.stringName(), current.cellQty(),
				current.cellBrand(), current.cellModel(), current.ratedCapacity(), current.cutoffVoltage(),
				current.floatVoltage(), current.boostVoltage(), current.rNew(), current.highResistanceThreshold(),
				current.highTemperatureThreshold(), current.lowVoltageThreshold(), current.highVoltageThreshold(),
				current.bridgeId(), current.startTaUnitId(), SocCheckpointPolicy.PERSIST_DATABASE);

		fixture.lifecycle.update(persistent);

		assertEquals("PERSIST_DATABASE", fixture.admin.byId("bmsHuasuTc-str1").properties
				.get("maxiems.socCheckpointPolicy"));
		assertEquals("PERSIST_DATABASE", fixture.admin.byId("ctrlBmsHuasuCalc-str1").properties
				.get("socCheckpointPolicy"));
		assertEquals(SocCheckpointPolicy.PERSIST_DATABASE, fixture.lifecycle.get(1).socCheckpointPolicy());
	}

	@Test
	public void wrongRetainedTargetIsReconciledOnce() throws Exception {
		var fixture = seeded(1);
		var ta = fixture.admin.byId(taId(1));
		ta.properties.put("Modbus.target", "(enabled=true)");
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(1));

		assertEquals(1, ta.updateCount);
		assertEquals(ConfigUtils.generateReferenceTargetFilter(ta.pid, "modbus0"),
				ta.properties.get("Modbus.target"));
		assertEquals(0, fixture.admin.byId("bmsHuasuTc-str1").updateCount);
		assertEquals(0, fixture.admin.byId("ctrlBmsHuasuCalc-str1").updateCount);
	}

	@Test
	public void decreaseDeletesOnlyExcessTaAndRetainsTcAndCalculator() throws Exception {
		var fixture = seeded(110);
		var tc = fixture.admin.byId("bmsHuasuTc-str1");
		var calculator = fixture.admin.byId("ctrlBmsHuasuCalc-str1");
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(11));

		assertEquals(99, fixture.admin.totalDeletes());
		for (var cell = 1; cell <= 11; cell++) assertFalse(fixture.admin.byId(taId(cell)).deleted);
		assertFalse(tc.deleted);
		assertFalse(calculator.deleted);
		assertEquals(1, tc.updateCount);
		assertEquals(1, calculator.updateCount);
	}

	@Test
	public void increaseCreatesOnlyAddedTaWithInitialTargets() throws Exception {
		var fixture = seeded(11);
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(110));

		assertEquals(99, fixture.admin.createdCount);
		assertEquals(0, fixture.admin.totalDeletes());
		for (var cell = 12; cell <= 110; cell++) {
			var added = fixture.admin.byId(taId(cell));
			assertEquals(1, added.updateCount);
			assertEquals(ConfigUtils.generateReferenceTargetFilter(added.pid, "modbus0"),
					added.properties.get("Modbus.target"));
		}
	}

	@Test
	public void metadataChangeUpdatesOnlyTc() throws Exception {
		var fixture = seeded(11);
		fixture.admin.resetEvents();
		var current = definition(11);
		var renamed = new BmsStringModels.Definition(1, "Renamed", current.cellQty(), current.cellBrand(),
				current.cellModel(), current.ratedCapacity(), current.cutoffVoltage(), current.floatVoltage(),
				current.boostVoltage(), current.rNew(), current.highResistanceThreshold(),
				current.highTemperatureThreshold(), current.lowVoltageThreshold(), current.highVoltageThreshold(),
				current.bridgeId(), current.startTaUnitId());

		fixture.lifecycle.update(renamed);

		assertEquals(1, fixture.admin.byId("bmsHuasuTc-str1").updateCount);
		assertEquals(0, fixture.admin.byId("ctrlBmsHuasuCalc-str1").updateCount);
		for (var cell = 1; cell <= 11; cell++) assertEquals(0, fixture.admin.byId(taId(cell)).updateCount);
	}

	@Test
	public void bridgeChangePreservesUnmanagedPropertiesAndUpdatesTargets() throws Exception {
		var fixture = seeded(2);
		var ta = fixture.admin.byId(taId(1));
		ta.properties.put("site.note", "preserve me");
		var calculator = fixture.admin.byId("ctrlBmsHuasuCalc-str1");
		calculator.properties.put("site.note", "preserve calculator note");
		fixture.admin.resetEvents();
		var current = definition(2);
		var moved = new BmsStringModels.Definition(1, current.stringName(), current.cellQty(), current.cellBrand(),
				current.cellModel(), current.ratedCapacity(), current.cutoffVoltage(), current.floatVoltage(),
				current.boostVoltage(), current.rNew(), current.highResistanceThreshold(),
				current.highTemperatureThreshold(), current.lowVoltageThreshold(), current.highVoltageThreshold(),
				"modbus1", current.startTaUnitId());

		fixture.lifecycle.update(moved);

		assertEquals("preserve me", ta.properties.get("site.note"));
		assertEquals("preserve calculator note", calculator.properties.get("site.note"));
		assertEquals(ConfigUtils.generateReferenceTargetFilter(ta.pid, "modbus1"),
				ta.properties.get("Modbus.target"));
		assertEquals(0, fixture.admin.createdCount);
		assertEquals(0, fixture.admin.totalDeletes());
	}

	@Test
	public void updateRejectsStartUnitChangeAndOtherStringBridgeCollision() throws Exception {
		var fixture = seeded(1);
		var current = definition(1);
		var changedStart = new BmsStringModels.Definition(1, current.stringName(), 1, current.cellBrand(),
				current.cellModel(), current.ratedCapacity(), current.cutoffVoltage(), current.floatVoltage(),
				current.boostVoltage(), current.rNew(), current.highResistanceThreshold(),
				current.highTemperatureThreshold(), current.lowVoltageThreshold(), current.highVoltageThreshold(),
				current.bridgeId(), 2);
		assertThrows(IllegalArgumentException.class, () -> fixture.lifecycle.update(changedStart));

		var otherTc = fixture.admin.create("BMS.Huasu.TC");
		var otherProperties = new Hashtable<String, Object>();
		otherProperties.put("id", "bmsHuasuTc-str2");
		otherProperties.put("modbusId", "modbus1");
		otherProperties.put("modbusUnitId", 206);
		otherTc.update(otherProperties);
		var collision = new BmsStringModels.Definition(1, current.stringName(), 1, current.cellBrand(),
				current.cellModel(), current.ratedCapacity(), current.cutoffVoltage(), current.floatVoltage(),
				current.boostVoltage(), current.rNew(), current.highResistanceThreshold(),
				current.highTemperatureThreshold(), current.lowVoltageThreshold(), current.highVoltageThreshold(),
				"modbus1", current.startTaUnitId());
		assertThrows(IllegalArgumentException.class, () -> fixture.lifecycle.update(collision));
	}

	@Test
	public void duplicateAndWrongFactoryRecordsAreRemovedUsingRealOsgiFilterSemantics() throws Exception {
		var fixture = seeded(1);
		var retained = fixture.admin.byId(taId(1));
		var duplicateProxy = fixture.admin.create("BMS.Huasu.TA");
		var duplicate = fixture.admin.configurations.get(fixture.admin.configurations.size() - 1);
		duplicateProxy.update(copy(retained.properties));
		var wrongFactoryProxy = fixture.admin.create("BMS.Huasu.TC");
		var wrongFactory = fixture.admin.configurations.get(fixture.admin.configurations.size() - 1);
		var wrongProperties = copy(retained.properties);
		wrongProperties.put("id", "ctrlBmsHuasuCalc-str1");
		wrongFactoryProxy.update(wrongProperties);
		fixture.admin.resetEvents();

		fixture.lifecycle.update(definition(1));

		assertFalse(retained.deleted);
		assertTrue(duplicate.deleted);
		assertTrue(wrongFactory.deleted);
		assertEquals("Controller.BmsHuasuTc.BulkCreator",
				fixture.admin.activeById("ctrlBmsHuasuCalc-str1").factoryPid);
	}

	@Test
	public void deleteFailureRollsBackUpdatesAndRecreatesEarlierExcess() throws Exception {
		var fixture = seeded(3);
		fixture.admin.resetEvents();
		fixture.admin.byId(taId(3)).failDelete = true;

		assertThrows(Exception.class, () -> fixture.lifecycle.update(definition(1)));

		assertNotNull(fixture.admin.activeById(taId(1)));
		assertNotNull(fixture.admin.activeById(taId(2)));
		assertNotNull(fixture.admin.activeById(taId(3)));
		var restoredTa = fixture.admin.activeById(taId(2));
		assertEquals(ConfigUtils.generateReferenceTargetFilter(restoredTa.pid, "modbus0"),
				restoredTa.properties.get("Modbus.target"));
		assertEquals(3, ((Number) fixture.admin.activeById("bmsHuasuTc-str1").properties
				.get("maxiems.cellQty")).intValue());
		assertEquals(3, ((Number) fixture.admin.activeById("ctrlBmsHuasuCalc-str1").properties
				.get("numberOfCells")).intValue());
	}

	@Test
	public void fullDeleteContinuesPastFailureAndReportsRemainingOrphan() throws Exception {
		var fixture = seeded(1);
		fixture.admin.byId(taId(1)).failDelete = true;

		var error = assertThrows(IllegalStateException.class, () -> fixture.lifecycle.delete(1));

		assertTrue(error.getMessage().contains(taId(1)));
		assertTrue(fixture.admin.byId("bmsHuasuTc-str1").deleted);
		assertTrue(fixture.admin.byId("ctrlBmsHuasuCalc-str1").deleted);
	}

	private static Fixture seeded(int cellCount) throws Exception {
		var fixture = new Fixture();
		fixture.lifecycle.create(definition(cellCount));
		return fixture;
	}

	private static String taId(int cell) {
		return "bmsHuasuTa-str1-cell" + cell;
	}

	private static BmsStringModels.Definition definition(int cellQty) {
		return new BmsStringModels.Definition(1, "String 1", cellQty, "brand", "model", 100d, 2.5d, 3.5d,
				3.6d, 100d, 200d, 50d, 2d, 4d, "modbus0", 1);
	}

	private static final class Fixture {
		private final RecordingConfigurationAdmin admin = new RecordingConfigurationAdmin();
		private final BmsStringLifecycle lifecycle = new BmsStringLifecycle();

		private Fixture() throws Exception {
			var diagnostics = new BmsStringReadinessDiagnostics();
			setField(diagnostics, "componentManager", proxy(ComponentManager.class, (method, arguments) -> {
				throw new IllegalArgumentException("No runtime components in lifecycle test");
			}));
			setField(this.lifecycle, "configurationAdmin", this.admin.proxy);
			setField(this.lifecycle, "readinessDiagnostics", diagnostics);
		}
	}

	private static final class RecordingConfigurationAdmin {
		private final List<RecordingConfiguration> configurations = new ArrayList<>();
		private int nextPid;
		private int createdCount;
		private final ConfigurationAdmin proxy = proxy(ConfigurationAdmin.class, (method, arguments) -> switch (method) {
		case "createFactoryConfiguration" -> this.create((String) arguments[0]);
		case "listConfigurations" -> this.list((String) arguments[0]);
		default -> defaultValue(method);
		});

		private Configuration create(String factoryPid) {
			var configuration = new RecordingConfiguration(factoryPid, factoryPid + "~" + ++this.nextPid);
			this.configurations.add(configuration);
			this.createdCount++;
			return configuration.proxy;
		}

		private Configuration[] list(String filter) throws Exception {
			var osgiFilter = filter == null ? null : FrameworkUtil.createFilter(filter);
			return this.configurations.stream().filter(value -> !value.deleted)
					.filter(value -> osgiFilter == null || osgiFilter.match(value.propertiesWithServiceMetadata()))
					.map(value -> value.proxy).toArray(Configuration[]::new);
		}

		private RecordingConfiguration byId(String id) {
			return this.configurations.stream().filter(value -> id.equals(value.properties.get("id")))
					.findFirst().orElseThrow();
		}

		private RecordingConfiguration activeById(String id) {
			return this.configurations.stream().filter(value -> !value.deleted && id.equals(value.properties.get("id")))
					.findFirst().orElse(null);
		}

		private void resetEvents() {
			this.createdCount = 0;
			for (var configuration : this.configurations) {
				configuration.updateCount = 0;
				configuration.deleteCount = 0;
			}
		}

		private int totalUpdates() {
			return this.configurations.stream().mapToInt(value -> value.updateCount).sum();
		}

		private int totalDeletes() {
			return this.configurations.stream().mapToInt(value -> value.deleteCount).sum();
		}
	}

	private static final class RecordingConfiguration {
		private final String factoryPid;
		private final String pid;
		private Dictionary<String, Object> properties = new Hashtable<>();
		private int updateCount;
		private int deleteCount;
		private boolean deleted;
		private boolean failDelete;
		private final Configuration proxy;

		@SuppressWarnings("unchecked")
		private RecordingConfiguration(String factoryPid, String pid) {
			this.factoryPid = factoryPid;
			this.pid = pid;
			this.proxy = proxy(Configuration.class, (method, arguments) -> switch (method) {
			case "getFactoryPid" -> this.factoryPid;
			case "getPid" -> this.pid;
			case "getProperties" -> this.propertiesWithServiceMetadata();
			case "update" -> {
				this.properties = copy((Dictionary<String, Object>) arguments[0]);
				this.updateCount++;
				yield null;
			}
			case "delete" -> {
				this.deleteCount++;
				if (this.failDelete) throw new IllegalStateException("delete failed for " + this.pid);
				this.deleted = true;
				yield null;
			}
			default -> defaultValue(method);
			});
		}

		private Dictionary<String, Object> propertiesWithServiceMetadata() {
			var result = copy(this.properties);
			result.put(Constants.SERVICE_PID, this.pid);
			result.put(ConfigurationAdmin.SERVICE_FACTORYPID, this.factoryPid);
			return result;
		}
	}

	private static Dictionary<String, Object> copy(Dictionary<String, Object> source) {
		Dictionary<String, Object> result = new Hashtable<>();
		var keys = source.keys();
		while (keys.hasMoreElements()) {
			var key = keys.nextElement();
			result.put(key, source.get(key));
		}
		return result;
	}

	private static Object defaultValue(String method) {
		return switch (method) {
		case "getChangeCount" -> 0L;
		case "getAttributes" -> 0;
		case "updateIfDifferent" -> false;
		default -> null;
		};
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		var field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
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
