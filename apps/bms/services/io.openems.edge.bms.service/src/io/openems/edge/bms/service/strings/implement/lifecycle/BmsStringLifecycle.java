package io.openems.edge.bms.service.strings.implement.lifecycle;

import io.openems.edge.bms.service.strings.model.BmsStringModels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.framework.Constants;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.common.utils.ConfigUtils;
import io.openems.edge.bms.service.calculation.model.EstimatorMode;
import io.openems.edge.bms.service.discharge.model.DischargeCurrentDirection;
import io.openems.edge.bms.service.strings.model.BmsStringModels.Definition;

/**
 * Creates and deletes exactly one Huasu TA/TC string at a time.
 * Component configurations remain the runtime source of truth; metadata is
 * saved as extra properties on that string's TC configuration.
 */
@Component(name = "io.openems.edge.bms.core.strings.BmsStringLifecycle", service = BmsStringLifecycle.class)
public class BmsStringLifecycle {

	private static final String TA_FACTORY = "BMS.Huasu.TA";
	private static final String TC_FACTORY = "BMS.Huasu.TC";
	private static final String CALCULATOR_FACTORY = "Controller.BmsHuasuTc.BulkCreator";
	private static final String MONITORING_FACTORY = "Controller.Bms.Monitoring";
	private static final int TC_UNIT_ID = 206;
	private static final Pattern TC_ID = Pattern.compile("bmsHuasuTc-str(\\d+)");

	@Reference
	private ConfigurationAdmin configurationAdmin;
	@Reference
	private BmsStringReadinessDiagnostics readinessDiagnostics;

	public synchronized Definition create(Definition request) throws Exception {
		if (request.stringIndex() < 1) {
			throw new IllegalArgumentException("String ID must use a positive index, for example str1");
		}
		var definition = withStringName(request, "str" + request.stringIndex());
		this.validate(definition);
		this.readinessDiagnostics.start("CREATE", definition, Set.of());
		return this.createComponents(definition);
	}

	private Definition createComponents(Definition definition) throws Exception {
		var created = new ArrayList<Configuration>();
		try {
			for (var cell = 1; cell <= definition.cellQty(); cell++) {
				var configuration = this.configurationAdmin.createFactoryConfiguration(TA_FACTORY, "?");
				created.add(configuration);
				configuration.update(componentProperties(configuration.getPid(), "bmsHuasuTa-" + definition.id() + "-cell" + cell,
						"BMS Huasu TA " + definition.id() + " cell " + cell, definition.bridgeId(), definition.startTaUnitId() + cell - 1));
			}
			var tc = this.configurationAdmin.createFactoryConfiguration(TC_FACTORY, "?");
			created.add(tc);
			var tcProperties = componentProperties(tc.getPid(), "bmsHuasuTc-" + definition.id(),
					"BMS Huasu TC " + definition.id(), definition.bridgeId(), TC_UNIT_ID);
			putMetadata(tcProperties, definition);
			tc.update(tcProperties);

			// The existing controller-tools calculator is now one instance per string.
			// It reads these TA/TC components but never creates or deletes them.
			var calculator = this.configurationAdmin.createFactoryConfiguration(CALCULATOR_FACTORY, "?");
			created.add(calculator);
			calculator.update(calculatorProperties(definition));
			var monitoring = this.configurationAdmin.createFactoryConfiguration(MONITORING_FACTORY, "?");
			created.add(monitoring);
			monitoring.update(monitoringProperties(definition, true));
			this.readinessDiagnostics.configAdminCompleted(definition.stringIndex());
			return definition;
		} catch (Exception e) {
			for (var configuration : created) {
				try { configuration.delete(); } catch (Exception rollbackError) { e.addSuppressed(rollbackError); }
			}
			this.readinessDiagnostics.failed(definition.stringIndex());
			throw e;
		}
	}

	/** Reconciles one string without replacing unchanged component configurations. */
	public synchronized Definition update(Definition request) throws Exception {
		if (request.stringIndex() < 1) {
			throw new IllegalArgumentException("String index is required for an update");
		}
		var previous = this.get(request.stringIndex());
		var definition = withStringName(request, previous.stringName());
		this.validateUpdate(definition);
		if (definition.startTaUnitId() != previous.startTaUnitId()) {
			throw new IllegalArgumentException("Changing the TA Modbus start unit ID is not supported");
		}
		this.validateAllocation(definition, definition.stringIndex());
		var plan = this.reconciliationPlan(definition);
		this.readinessDiagnostics.start("UPDATE", definition, plan.retainedComponentIds());
		try {
			this.apply(plan);
			this.readinessDiagnostics.configAdminCompleted(definition.stringIndex());
			return definition;
		} catch (Exception updateError) {
			this.readinessDiagnostics.failed(definition.stringIndex());
			throw updateError;
		}
	}

	private static Definition withStringName(Definition request, String stringName) {
		return new Definition(request.stringIndex(), stringName, request.cellQty(), request.cellBrand(),
				request.cellModel(), request.ratedCapacity(), request.cutoffVoltage(), request.floatVoltage(),
				request.boostVoltage(), request.rNew(), request.highResistanceThreshold(),
				request.highTemperatureThreshold(), request.lowVoltageThreshold(), request.highVoltageThreshold(),
				request.bridgeId(), request.startTaUnitId(), request.socCheckpointPolicy());
	}

	private ReconciliationPlan reconciliationPlan(Definition definition) throws Exception {
		var configurations = this.stringConfigurations(definition.stringIndex());
		var byId = new LinkedHashMap<String, List<Configuration>>();
		for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (properties == null) continue;
			byId.computeIfAbsent(string(properties.get("id")), key -> new ArrayList<>()).add(configuration);
		}
		byId.values().forEach(values -> values.sort(Comparator.comparing(Configuration::getPid)));

		var desired = new LinkedHashMap<String, DesiredConfiguration>();
		for (var cell = 1; cell <= definition.cellQty(); cell++) {
			var id = "bmsHuasuTa-" + definition.id() + "-cell" + cell;
			desired.put(id, new DesiredConfiguration(TA_FACTORY, id,
					"BMS Huasu TA " + definition.id() + " cell " + cell,
					definition.bridgeId(), definition.startTaUnitId() + cell - 1, false));
		}
		var tcId = "bmsHuasuTc-" + definition.id();
		desired.put(tcId, new DesiredConfiguration(TC_FACTORY, tcId, "BMS Huasu TC " + definition.id(),
				definition.bridgeId(), TC_UNIT_ID, true));
		var calculatorId = "ctrlBmsHuasuCalc-" + definition.id();
		desired.put(calculatorId, new DesiredConfiguration(CALCULATOR_FACTORY, calculatorId, null, null, 0, false));
		var monitoringId = "ctrlBmsMonitoring-" + definition.id();
		desired.put(monitoringId, new DesiredConfiguration(MONITORING_FACTORY, monitoringId, null, null, 0, false));
		var dischargeCurrentDirection = existingDirection(byId.get(monitoringId));
		var estimatorMode = existingEstimatorMode(byId.get(calculatorId));

		var retained = new LinkedHashSet<String>();
		var updates = new ArrayList<ConfigurationMutation>();
		var creates = new ArrayList<DesiredConfiguration>();
		var deletes = new ArrayList<Configuration>();
		for (var entry : desired.entrySet()) {
			var matches = byId.remove(entry.getKey());
			if (matches == null || matches.isEmpty()) {
				creates.add(entry.getValue());
				continue;
			}
			Configuration primary = null;
			for (var configuration : matches) {
				if (entry.getValue().factoryPid().equals(configuration.getFactoryPid())) {
					primary = configuration;
					break;
				}
			}
			if (primary == null) {
				creates.add(entry.getValue());
				deletes.addAll(matches);
				continue;
			}
			retained.add(entry.getKey());
			var expected = this.desiredProperties(primary, entry.getValue(), definition, false);
			if (CALCULATOR_FACTORY.equals(entry.getValue().factoryPid())) {
				expected.put("dischargeCurrentDirection", dischargeCurrentDirection);
				expected.put("estimatorMode", estimatorMode);
			}
			if (!propertiesMatch(primary.getProperties(), expected)) {
				var merged = copyProperties(primary.getProperties());
				putAll(merged, expected);
				updates.add(new ConfigurationMutation(primary, merged));
			}
			for (var configuration : matches) {
				if (configuration != primary) deletes.add(configuration);
			}
		}
		for (var remaining : byId.values()) deletes.addAll(remaining);
		deletes.sort(Comparator.comparing(Configuration::getPid));
		return new ReconciliationPlan(definition, List.copyOf(updates), List.copyOf(creates), List.copyOf(deletes),
				Set.copyOf(retained));
	}

	private static String existingDirection(List<Configuration> monitoringConfigurations) {
		var direction = validDirection(monitoringConfigurations);
		return direction == null ? DischargeCurrentDirection.NEGATIVE.name() : direction;
	}

	private static String existingDirection(List<Configuration> monitoringConfigurations,
			List<Configuration> calculatorConfigurations) {
		var direction = validDirection(monitoringConfigurations);
		if (direction == null) {
			direction = validDirection(calculatorConfigurations);
		}
		return direction == null ? DischargeCurrentDirection.NEGATIVE.name() : direction;
	}

	private static String validDirection(List<Configuration> configurations) {
		if (configurations == null) return null;
		for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (properties == null) continue;
			var direction = properties.get("dischargeCurrentDirection");
			if (direction == null) continue;
			try {
				return DischargeCurrentDirection.parse(string(direction)).name();
			} catch (IllegalArgumentException ignored) {
				// Missing/obsolete values are repaired to a valid persisted direction.
			}
		}
		return null;
	}

	private static String existingEstimatorMode(List<Configuration> calculatorConfigurations) {
		if (calculatorConfigurations != null) {
			for (var configuration : calculatorConfigurations) {
				var properties = configuration.getProperties();
				if (properties == null || properties.get("estimatorMode") == null) continue;
				try {
					return EstimatorMode.parse(String.valueOf(properties.get("estimatorMode"))).name();
				} catch (IllegalArgumentException ignored) {
					// Reconcile obsolete/invalid values to the supported automatic policy.
				}
			}
		}
		return EstimatorMode.AUTO.name();
	}

	private Dictionary<String, Object> desiredProperties(Configuration configuration, DesiredConfiguration desired,
			Definition definition, boolean includeMonitoringDirectionDefault) {
		if (CALCULATOR_FACTORY.equals(desired.factoryPid())) return calculatorProperties(definition);
		if (MONITORING_FACTORY.equals(desired.factoryPid())) {
			return monitoringProperties(definition, includeMonitoringDirectionDefault);
		}
		var properties = componentProperties(configuration.getPid(), desired.id(), desired.alias(), desired.bridgeId(),
				desired.unitId());
		if (desired.metadata()) putMetadata(properties, definition);
		return properties;
	}

	private void apply(ReconciliationPlan plan) throws Exception {
		var created = new ArrayList<Configuration>();
		var updated = new ArrayList<ConfigurationMutation>();
		var deleted = new ArrayList<DeletedConfiguration>();
		try {
			for (var mutation : plan.updates()) {
				var previous = copyProperties(mutation.configuration().getProperties());
				updated.add(new ConfigurationMutation(mutation.configuration(), previous));
				mutation.configuration().update(mutation.properties());
			}
			for (var desired : plan.creates()) {
				var configuration = this.configurationAdmin.createFactoryConfiguration(desired.factoryPid(), "?");
				created.add(configuration);
				configuration.update(this.desiredProperties(configuration, desired, plan.definition(), true));
			}
			for (var configuration : plan.deletes()) {
				var snapshot = new DeletedConfiguration(configuration.getFactoryPid(),
						copyProperties(configuration.getProperties()));
				configuration.delete();
				deleted.add(snapshot);
			}
		} catch (Exception failure) {
			for (var configuration : reversed(created)) {
				try { configuration.delete(); } catch (Exception rollbackError) { failure.addSuppressed(rollbackError); }
			}
			for (var mutation : reversed(updated)) {
				try { mutation.configuration().update(mutation.properties()); } catch (Exception rollbackError) { failure.addSuppressed(rollbackError); }
			}
			for (var snapshot : deleted) {
				try {
					var configuration = this.configurationAdmin.createFactoryConfiguration(snapshot.factoryPid(), "?");
					var properties = copyProperties(snapshot.properties());
					if (TA_FACTORY.equals(snapshot.factoryPid()) || TC_FACTORY.equals(snapshot.factoryPid())) {
						properties.put("Modbus.target", ConfigUtils.generateReferenceTargetFilter(configuration.getPid(),
								string(properties.get("modbusId"))));
					}
					configuration.update(properties);
				} catch (Exception rollbackError) {
					failure.addSuppressed(rollbackError);
				}
			}
			throw failure;
		}
	}

	private void validateUpdate(Definition definition) throws Exception {
		if (definition.stringName() == null || definition.stringName().isBlank()) throw new IllegalArgumentException("String name is required");
		if (definition.cellQty() < 1 || definition.cellQty() > 200) throw new IllegalArgumentException("Cell quantity must be between 1 and 200");
		if (definition.bridgeId() == null || definition.bridgeId().isBlank()) throw new IllegalArgumentException("A dedicated Modbus bridge is required");
		if (definition.startTaUnitId() < 1 || definition.startTaUnitId() + definition.cellQty() - 1 > 247) throw new IllegalArgumentException("TA Modbus unit range is invalid");
		this.rejectReservedBridge(definition.bridgeId());
	}

	public synchronized void delete(int stringIndex) throws Exception {
		var configurations = this.stringConfigurations(stringIndex);
		if (configurations == null || configurations.length == 0) throw new IllegalArgumentException("Unknown string " + stringIndex);
		Exception deleteFailure = null;
		for (var configuration : configurations) {
			try {
				configuration.delete();
			} catch (Exception e) {
				if (deleteFailure == null) deleteFailure = new IllegalStateException("Failed to delete complete string " + stringIndex);
				deleteFailure.addSuppressed(e);
			}
		}
		var remaining = this.stringConfigurations(stringIndex);
		if (remaining.length > 0) {
			var ids = new ArrayList<String>();
			for (var configuration : remaining) ids.add(configurationId(configuration));
			var verificationFailure = new IllegalStateException("String " + stringIndex
					+ " still has matching configurations after delete: " + ids);
			if (deleteFailure != null) verificationFailure.addSuppressed(deleteFailure);
			throw verificationFailure;
		}
		if (deleteFailure != null) throw deleteFailure;
	}

	private Configuration[] stringConfigurations(int stringIndex) throws Exception {
		var id = "str" + stringIndex;
		var filter = "(&(|(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + TA_FACTORY
				+ ")(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + TC_FACTORY
				+ ")(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + CALCULATOR_FACTORY
				+ ")(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + MONITORING_FACTORY + "))(|(id=bmsHuasuTa-" + id
				+ "-cell*)(id=bmsHuasuTc-" + id + ")(id=ctrlBmsHuasuCalc-" + id
				+ ")(id=ctrlBmsMonitoring-" + id + ")))";
		var configurations = this.configurationAdmin.listConfigurations(filter);
		return configurations == null ? new Configuration[0] : configurations;
	}

	public List<Definition> list() throws Exception {
		var configurations = this.configurationAdmin.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + TC_FACTORY + ")");
		var result = new ArrayList<Definition>();
		if (configurations != null) for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (properties == null) continue;
			var matcher = TC_ID.matcher(string(properties.get("id")));
			if (matcher.matches()) result.add(fromProperties(Integer.parseInt(matcher.group(1)), properties));
		}
		result.sort(java.util.Comparator.comparing(Definition::stringIndex));
		return result;
	}

	public Definition get(int stringIndex) throws Exception {
		return this.list().stream().filter(definition -> definition.stringIndex() == stringIndex).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown string " + stringIndex));
	}

	/**
	 * Ensures strings created by an older controller-tools version also own the
	 * dedicated calculator expected by the current data API. This is idempotent:
	 * an existing correct configuration is not written again.
	 */
	public synchronized void reconcileCalculator(Definition definition) throws Exception {
		var calculatorId = "ctrlBmsHuasuCalc-" + definition.id();
		var calculatorFilter = "(&(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + CALCULATOR_FACTORY + ")(id="
				+ calculatorId + "))";
		var calculators = this.configurationAdmin.listConfigurations(calculatorFilter);
		calculators = calculators == null ? new Configuration[0] : calculators;

		var monitoringId = "ctrlBmsMonitoring-" + definition.id();
		var monitoringFilter = "(&(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + MONITORING_FACTORY + ")(id="
				+ monitoringId + "))";
		var monitoring = this.configurationAdmin.listConfigurations(monitoringFilter);
		monitoring = monitoring == null ? new Configuration[0] : monitoring;

		var direction = existingDirection(List.of(monitoring), List.of(calculators));
		var estimatorMode = existingEstimatorMode(List.of(calculators));
		var expected = calculatorProperties(definition);
		expected.put("dischargeCurrentDirection", direction);
		expected.put("estimatorMode", estimatorMode);
		if (calculators.length == 0) {
			var calculator = this.configurationAdmin.createFactoryConfiguration(CALCULATOR_FACTORY, "?");
			calculator.update(expected);
		} else {
			var primary = calculators[0];
			if (!calculatorMatches(primary.getProperties(), expected)) {
				var merged = copyProperties(primary.getProperties());
				putAll(merged, expected);
				primary.update(merged);
			}
			// Duplicate configurations with the same component ID cannot be activated
			// reliably. Keep one authoritative instance for this string.
			for (var i = 1; i < calculators.length; i++) {
				calculators[i].delete();
			}
		}
		this.reconcileMonitoring(definition, direction, monitoring);
	}

	private void reconcileMonitoring(Definition definition, String direction, Configuration[] configurations)
			throws Exception {
		var expected = monitoringProperties(definition, false);
		expected.put("dischargeCurrentDirection", direction);
		if (configurations.length == 0) {
			var monitoring = this.configurationAdmin.createFactoryConfiguration(MONITORING_FACTORY, "?");
			monitoring.update(expected);
			return;
		}
		var primary = configurations[0];
		if (!propertiesMatch(primary.getProperties(), expected)) {
			var merged = copyProperties(primary.getProperties());
			putAll(merged, expected);
			primary.update(merged);
		}
		for (var i = 1; i < configurations.length; i++) configurations[i].delete();
	}

	private void validate(Definition definition) throws Exception {
		if (definition.stringName() == null || definition.stringName().isBlank()) throw new IllegalArgumentException("String name is required");
		if (definition.cellQty() < 1 || definition.cellQty() > 200) throw new IllegalArgumentException("Cell quantity must be between 1 and 200");
		if (definition.bridgeId() == null || definition.bridgeId().isBlank()) throw new IllegalArgumentException("A dedicated Modbus bridge is required");
		if (definition.startTaUnitId() < 1 || definition.startTaUnitId() + definition.cellQty() - 1 > 247) throw new IllegalArgumentException("TA Modbus unit range is invalid");
		if (this.list().stream().anyMatch(value -> value.stringIndex() == definition.stringIndex())) throw new IllegalArgumentException("String ID str" + definition.stringIndex() + " already exists");
		this.rejectReservedBridge(definition.bridgeId());

		this.validateAllocation(definition, null);
	}

	private void rejectReservedBridge(String bridgeId) throws Exception {
		var bridges = this.configurationAdmin.listConfigurations("(&(|(" + ConfigurationAdmin.SERVICE_FACTORYPID
				+ "=Bridge.Modbus.Serial)(" + ConfigurationAdmin.SERVICE_FACTORYPID
				+ "=Bridge.BmsModbus.Serial))(id=" + bridgeId.replace("\\", "\\5c").replace("*", "\\2a")
						.replace("(", "\\28").replace(")", "\\29") + "))");
		if (bridges == null) return;
		for (var bridge : bridges) {
			var properties = bridge.getProperties();
			if (properties != null && !string(properties.get("maxiems.modbusServerReservedBy")).isBlank()) {
				throw new IllegalStateException("Bridge [" + bridgeId + "] is reserved by the BMS Modbus Server");
			}
		}
	}

	private void validateAllocation(Definition definition, Integer excludedStringIndex) throws Exception {
		var excludedId = excludedStringIndex == null ? null : "str" + excludedStringIndex;
		var tcConfigurations = this.configurationAdmin.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + TC_FACTORY + ")");
		if (tcConfigurations != null) for (var configuration : tcConfigurations) {
			var properties = configuration.getProperties();
			if (properties != null && belongsToString(properties, excludedId)) continue;
			if (properties != null && definition.bridgeId().equals(string(properties.get("modbusId"))) && integer(properties.get("modbusUnitId")) == TC_UNIT_ID) {
				throw new IllegalArgumentException("Bridge [" + definition.bridgeId() + "] already uses fixed TC unit 206");
			}
		}

		var taConfigurations = this.configurationAdmin.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + TA_FACTORY + ")");
		if (taConfigurations != null) for (var configuration : taConfigurations) {
			var properties = configuration.getProperties();
			if (properties != null && belongsToString(properties, excludedId)) continue;
			if (properties == null || !definition.bridgeId().equals(string(properties.get("modbusId")))) continue;
			var unit = integer(properties.get("modbusUnitId"));
			if (unit >= definition.startTaUnitId() && unit < definition.startTaUnitId() + definition.cellQty()) throw new IllegalArgumentException("TA Modbus unit " + unit + " is already allocated on bridge [" + definition.bridgeId() + "]");
		}
	}

	private static boolean belongsToString(Dictionary<String, Object> properties, String stringId) {
		if (stringId == null) return false;
		var id = string(properties.get("id"));
		return id.equals("bmsHuasuTc-" + stringId) || id.equals("ctrlBmsHuasuCalc-" + stringId)
				|| id.startsWith("bmsHuasuTa-" + stringId + "-cell");
	}

	private static Dictionary<String, Object> componentProperties(String pid, String id, String alias, String bridgeId,
			int unitId) {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("id", id); properties.put("alias", alias); properties.put("enabled", true);
		properties.put("modbusId", bridgeId); properties.put("modbusUnitId", unitId);
		properties.put("Modbus.target", ConfigUtils.generateReferenceTargetFilter(pid, bridgeId));
		return properties;
	}

	private static Dictionary<String, Object> calculatorProperties(Definition definition) {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("id", "ctrlBmsHuasuCalc-" + definition.id());
		properties.put("alias", "BMS Huasu calculator " + definition.id());
		properties.put("enabled", true);
		properties.put("numberOfCells", definition.cellQty());
		properties.put("modbusId", definition.bridgeId());
		properties.put("startUnitId", definition.startTaUnitId());
		properties.put("stringId", String.valueOf(definition.stringIndex()));
		properties.put("stringKey", definition.id());
		properties.put("cellCapacityAh", Math.max(1, (int) Math.round(definition.ratedCapacity())));
		properties.put("cellCutoffVoltage", definition.cutoffVoltage());
		properties.put("cellFloatVoltage", definition.floatVoltage());
		properties.put("ratedResistanceUohm", Math.max(1, (int) Math.round(definition.rNew())));
		properties.put("estimatorMode", EstimatorMode.AUTO.name());
		properties.put("dischargeCurrentDirection", "NEGATIVE");
		properties.put("socCheckpointPolicy", definition.socCheckpointPolicy().name());
		return properties;
	}

	private static Dictionary<String, Object> monitoringProperties(Definition definition,
			boolean includeDirectionDefault) {
		Dictionary<String, Object> properties = new Hashtable<>();
		properties.put("id", "ctrlBmsMonitoring-" + definition.id());
		properties.put("alias", "BMS monitoring " + definition.id());
		properties.put("enabled", true);
		properties.put("stringKey", definition.id());
		properties.put("calculatorId", "ctrlBmsHuasuCalc-" + definition.id());
		if (includeDirectionDefault) properties.put("dischargeCurrentDirection", "NEGATIVE");
		return properties;
	}

	private static boolean calculatorMatches(Dictionary<String, Object> actual, Dictionary<String, Object> expected) {
		return propertiesMatch(actual, expected);
	}

	private static boolean propertiesMatch(Dictionary<String, Object> actual, Dictionary<String, Object> expected) {
		if (actual == null) return false;
		var keys = expected.keys();
		while (keys.hasMoreElements()) {
			var key = keys.nextElement();
			var actualValue = actual.get(key);
			var expectedValue = expected.get(key);
			if (actualValue instanceof Number actualNumber && expectedValue instanceof Number expectedNumber) {
				if (Double.compare(actualNumber.doubleValue(), expectedNumber.doubleValue()) != 0) return false;
			} else if (!Objects.equals(actualValue, expectedValue)) {
				return false;
			}
		}
		return true;
	}

	private static void putMetadata(Dictionary<String, Object> properties, Definition definition) {
		properties.put("maxiems.stringName", definition.stringName()); properties.put("maxiems.cellQty", definition.cellQty());
		properties.put("maxiems.cellBrand", definition.cellBrand()); properties.put("maxiems.cellModel", definition.cellModel());
		properties.put("maxiems.ratedCapacity", definition.ratedCapacity()); properties.put("maxiems.cutoffVoltage", definition.cutoffVoltage());
		properties.put("maxiems.floatVoltage", definition.floatVoltage()); properties.put("maxiems.boostVoltage", definition.boostVoltage()); properties.put("maxiems.rNew", definition.rNew());
		properties.put("maxiems.highResistanceThreshold", definition.highResistanceThreshold()); properties.put("maxiems.highTemperatureThreshold", definition.highTemperatureThreshold());
		properties.put("maxiems.lowVoltageThreshold", definition.lowVoltageThreshold()); properties.put("maxiems.highVoltageThreshold", definition.highVoltageThreshold());
		properties.put("maxiems.startTaUnitId", definition.startTaUnitId());
		properties.put("maxiems.socCheckpointPolicy", definition.socCheckpointPolicy().name());
	}

	private static Definition fromProperties(int index, Dictionary<String, Object> properties) {
		return new Definition(index, string(properties.get("maxiems.stringName"), "String " + index), integer(properties.get("maxiems.cellQty")),
				string(properties.get("maxiems.cellBrand")), string(properties.get("maxiems.cellModel")), decimal(properties.get("maxiems.ratedCapacity")),
				decimal(properties.get("maxiems.cutoffVoltage")), decimal(properties.get("maxiems.floatVoltage")), decimal(properties.get("maxiems.boostVoltage")),
				decimal(properties.get("maxiems.rNew")), decimal(properties.get("maxiems.highResistanceThreshold")), decimal(properties.get("maxiems.highTemperatureThreshold")),
				decimal(properties.get("maxiems.lowVoltageThreshold")), decimal(properties.get("maxiems.highVoltageThreshold")), string(properties.get("modbusId")), integer(properties.get("maxiems.startTaUnitId")),
				io.openems.edge.bms.service.strings.model.SocCheckpointPolicy.parse(string(properties.get("maxiems.socCheckpointPolicy"))));
	}

	private static String string(Object value) { return string(value, ""); }
	private static String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
	private static int integer(Object value) { return value instanceof Number number ? number.intValue() : value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
	private static double decimal(Object value) { return value instanceof Number number ? number.doubleValue() : value == null ? 0 : Double.parseDouble(String.valueOf(value)); }

	private static String configurationId(Configuration configuration) {
		var properties = configuration.getProperties();
		return properties == null ? configuration.getPid() : string(properties.get("id"), configuration.getPid());
	}

	private static Dictionary<String, Object> copyProperties(Dictionary<String, Object> source) {
		Dictionary<String, Object> copy = new Hashtable<>();
		if (source == null) return copy;
		var keys = source.keys();
		while (keys.hasMoreElements()) {
			var key = keys.nextElement();
			if (Constants.SERVICE_PID.equals(key) || ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)) continue;
			copy.put(key, source.get(key));
		}
		return copy;
	}

	private static void putAll(Dictionary<String, Object> target, Dictionary<String, Object> source) {
		var keys = source.keys();
		while (keys.hasMoreElements()) {
			var key = keys.nextElement();
			target.put(key, source.get(key));
		}
	}

	private static <T> List<T> reversed(List<T> values) {
		var result = new ArrayList<>(values);
		Collections.reverse(result);
		return result;
	}

	private record DesiredConfiguration(String factoryPid, String id, String alias, String bridgeId, int unitId,
			boolean metadata) { }

	private record ConfigurationMutation(Configuration configuration, Dictionary<String, Object> properties) { }

	private record DeletedConfiguration(String factoryPid, Dictionary<String, Object> properties) { }

	private record ReconciliationPlan(Definition definition, List<ConfigurationMutation> updates,
			List<DesiredConfiguration> creates, List<Configuration> deletes, Set<String> retainedComponentIds) { }
}
