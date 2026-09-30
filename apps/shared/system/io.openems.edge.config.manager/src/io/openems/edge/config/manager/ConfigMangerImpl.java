package io.openems.edge.config.manager;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.session.Role;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.EdgeGuards;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.config.manager.jsonrpc.ExportConfigs;
import io.openems.edge.config.manager.jsonrpc.ImportConfigs;

@Designate(ocd = Config.class, factory = true)
@Component(
		name = "Config.Manager",
		immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ConfigMangerImpl extends AbstractOpenemsComponent
		implements ConfigManger, OpenemsComponent, ComponentJsonApi {

	private static final String JSON_KEY_FACTORY_PID = "factoryPid";
	private static final String JSON_KEY_PROPERTIES = "properties";
	private static final String JSON_KEY_FELIX_TYPE = "felixType";
	private static final String JSON_KEY_VALUE = "value";

	private static final Set<String> EXCLUDED_PIDS = Set.of("org.apache.felix.fileinstall");
	private static final List<String> EXCLUDED_PID_PREFIXES = List.of(
			"org.apache.felix.",
			"org.osgi.",
			"osgi.",
			"system.");
	private static final Set<String> EXCLUDED_PROPERTY_KEYS = Set.of(
			"service.bundleLocation",
			"service.changecount",
			"service.factoryPid",
			"service.pid",
			"webconsole.configurationFactory.nameHint",
			"felix.fileinstall.filename");

	@Reference
	private ConfigurationAdmin configurationAdmin;

	private Config config;

	public ConfigMangerImpl() {
		super(
				OpenemsComponent.ChannelId.values(),
				ConfigManger.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(new ExportConfigs(), endpoint -> {
			endpoint.setDescription("Exports all user-relevant OSGi configurations.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
		}, call -> new ExportConfigs.Response(this.exportAllConfigsToJson()));

		builder.handleRequest(new ImportConfigs(), endpoint -> {
			endpoint.setDescription("Imports OSGi configurations from JSON.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
		}, call -> {
			this.importConfigsFromJson(call.getRequest().data());
			return EmptyObject.INSTANCE;
		});
	}

	@Override
	public JsonObject exportAllConfigsToJson() throws IOException {
		final var result = new JsonObject();
		final Configuration[] configurations;
		try {
			configurations = this.configurationAdmin.listConfigurations(null);
		} catch (InvalidSyntaxException e) {
			throw new IOException("Unable to list configurations.", e);
		}

		if (configurations == null) {
			return result;
		}

		for (var configuration : configurations) {
			if (!this.shouldExport(configuration)) {
				continue;
			}

			final var configJson = new JsonObject();
			if (configuration.getFactoryPid() != null) {
				configJson.addProperty(JSON_KEY_FACTORY_PID, configuration.getFactoryPid());
			}
			configJson.add(JSON_KEY_PROPERTIES, this.toJson(configuration.getProperties()));
			result.add(configuration.getPid(), configJson);
		}

		return result;
	}

	@Override
	public void importConfigsFromJson(JsonObject data) throws IOException {
		if (data == null) {
			return;
		}

		for (var entry : data.entrySet()) {
			final var pid = entry.getKey();
			if (this.isExcludedPid(pid) || !entry.getValue().isJsonObject()) {
				continue;
			}

			final var configJson = entry.getValue().getAsJsonObject();
			final var propertiesJson = this.getPropertiesJson(configJson);
			final var properties = this.fromJson(propertiesJson);
			this.deleteExistingConfiguration(pid, configJson, properties);
			final Configuration configuration = this.getConfigurationForImport(pid, configJson);

			configuration.update(properties);
		}
	}

	private void deleteExistingConfiguration(String pid, JsonObject configJson, Dictionary<String, Object> properties)
			throws IOException {
		try {
			final var configurations = this.configurationAdmin.listConfigurations(null);
			if (configurations == null) {
				return;
			}

			final var factoryPid = configJson.has(JSON_KEY_FACTORY_PID)
					? configJson.get(JSON_KEY_FACTORY_PID).getAsString()
					: null;
			final var componentId = this.getString(properties, "id");
			final var deletedPids = new HashSet<String>();

			for (var configuration : configurations) {
				if (!this.shouldDeleteConfiguration(configuration, pid, factoryPid, componentId)) {
					continue;
				}
				if (!deletedPids.add(configuration.getPid())) {
					continue;
				}
				configuration.delete();
			}
		} catch (InvalidSyntaxException e) {
			throw new IOException("Unable to delete existing configuration for PID [" + pid + "].", e);
		}
	}

	private Configuration getConfigurationForImport(String pid, JsonObject configJson) throws IOException {
		if (configJson.has(JSON_KEY_FACTORY_PID)) {
			final var factoryPid = configJson.get(JSON_KEY_FACTORY_PID).getAsString();
			final var factoryName = this.getFactoryName(pid, factoryPid);
			if (factoryName != null) {
				return this.configurationAdmin.getFactoryConfiguration(factoryPid, factoryName, "?");
			}
			return this.configurationAdmin.createFactoryConfiguration(factoryPid, "?");
		}
		return this.configurationAdmin.getConfiguration(pid, "?");
	}

	private boolean shouldExport(Configuration configuration) {
		if (configuration == null) {
			return false;
		}

		final var pid = configuration.getPid();
		if (this.isExcludedPid(pid)) {
			return false;
		}

		final var properties = configuration.getProperties();
		return properties != null && properties.keys().hasMoreElements();
	}

	private boolean isExcludedPid(String pid) {
		if (pid == null || pid.isBlank()) {
			return true;
		}
		if (EXCLUDED_PIDS.contains(pid)) {
			return true;
		}
		return EXCLUDED_PID_PREFIXES.stream().anyMatch(pid::startsWith);
	}

	private JsonObject toJson(Dictionary<String, Object> properties) {
		final var json = new JsonObject();
		if (properties == null) {
			return json;
		}

		final Enumeration<String> keys = properties.keys();
		while (keys.hasMoreElements()) {
			final var key = keys.nextElement();
			if (EXCLUDED_PROPERTY_KEYS.contains(key)) {
				continue;
			}

			final var value = properties.get(key);
			if (value != null) {
				json.add(key, this.encodeValue(value));
			}
		}

		return json;
	}

	private Hashtable<String, Object> fromJson(JsonObject propertiesJson) {
		final var result = new Hashtable<String, Object>();
		if (propertiesJson == null) {
			return result;
		}

		for (var entry : propertiesJson.entrySet()) {
			final var value = this.decodeValue(entry.getValue());
			if (value != null) {
				result.put(entry.getKey(), value);
			}
		}
		return result;
	}

	private JsonObject getPropertiesJson(JsonObject configJson) {
		if (configJson.has(JSON_KEY_PROPERTIES) && configJson.get(JSON_KEY_PROPERTIES).isJsonObject()) {
			return configJson.getAsJsonObject(JSON_KEY_PROPERTIES);
		}
		return configJson;
	}

	private String getFactoryName(String pid, String factoryPid) {
		final var prefix = factoryPid + ".";
		if (pid != null && factoryPid != null && pid.startsWith(prefix) && pid.length() > prefix.length()) {
			return pid.substring(prefix.length());
		}
		return null;
	}

	private boolean shouldDeleteConfiguration(Configuration configuration, String pid, String factoryPid, String componentId) {
		if (configuration == null) {
			return false;
		}
		if (pid.equals(configuration.getPid())) {
			return true;
		}

		final var existingProperties = configuration.getProperties();
		if (existingProperties == null) {
			return false;
		}

		if (pid.equals(this.getString(existingProperties, "service.pid"))) {
			return true;
		}
		if (factoryPid == null || componentId == null) {
			return false;
		}
		return factoryPid.equals(configuration.getFactoryPid())
				&& componentId.equals(this.getString(existingProperties, "id"));
	}

	private String getString(Dictionary<String, Object> properties, String key) {
		if (properties == null) {
			return null;
		}
		final var value = properties.get(key);
		return value != null ? String.valueOf(value) : null;
	}

	private JsonElement encodeValue(Object value) {
		if (value == null) {
			return JsonNull.INSTANCE;
		}

		if (value.getClass().isArray()) {
			final var wrapper = new JsonObject();
			wrapper.addProperty(JSON_KEY_FELIX_TYPE, this.getArrayFelixType(value));
			final var array = new JsonArray();
			final int length = Array.getLength(value);
			for (int i = 0; i < length; i++) {
				array.add(this.encodeValue(Array.get(value, i)));
			}
			wrapper.add(JSON_KEY_VALUE, array);
			return wrapper;
		}

		final var wrapper = new JsonObject();
		wrapper.addProperty(JSON_KEY_FELIX_TYPE, this.getFelixType(value));
		wrapper.add(JSON_KEY_VALUE, this.toPrimitive(value));
		return wrapper;
	}

	private JsonElement toPrimitive(Object value) {
		if (value instanceof Number number) {
			return new JsonPrimitive(number);
		}
		if (value instanceof Boolean bool) {
			return new JsonPrimitive(bool);
		}
		if (value instanceof Character character) {
			return new JsonPrimitive(character);
		}
		return new JsonPrimitive(String.valueOf(value));
	}

	private Object decodeValue(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}

		if (!element.isJsonObject()) {
			return this.decodePrimitiveWithoutType(element);
		}

		final var wrapper = element.getAsJsonObject();
		if (!wrapper.has(JSON_KEY_FELIX_TYPE) || !wrapper.has(JSON_KEY_VALUE)) {
			return this.decodePrimitiveWithoutType(wrapper);
		}

		final var type = wrapper.get(JSON_KEY_FELIX_TYPE).getAsString();
		final var value = wrapper.get(JSON_KEY_VALUE);
		if (type.endsWith("[]")) {
			return this.decodeArray(type, value.getAsJsonArray());
		}
		return this.decodeTypedValue(type, value);
	}

	private Object decodePrimitiveWithoutType(JsonElement element) {
		if (element.isJsonPrimitive()) {
			final var primitive = element.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				return primitive.getAsBoolean();
			}
			if (primitive.isNumber()) {
				return primitive.getAsDouble();
			}
			if (primitive.isString()) {
				return primitive.getAsString();
			}
		}
		return element.toString();
	}

	private Object decodeArray(String type, JsonArray array) {
		final var values = new Object[array.size()];
		for (int i = 0; i < array.size(); i++) {
			values[i] = this.decodeValue(array.get(i));
		}
		return this.toTypedArray(type, values);
	}

	private Object decodeTypedValue(String type, JsonElement value) {
		return switch (type) {
		case "I" -> value.getAsInt();
		case "L" -> value.getAsLong();
		case "F" -> value.getAsFloat();
		case "D" -> value.getAsDouble();
		case "X" -> value.getAsByte();
		case "S" -> value.getAsShort();
		case "C" -> value.getAsString().charAt(0);
		case "B" -> value.getAsBoolean();
		default -> value.getAsString();
		};
	}

	private String getFelixType(Object value) {
		if (value instanceof Integer) {
			return "I";
		}
		if (value instanceof Long) {
			return "L";
		}
		if (value instanceof Float) {
			return "F";
		}
		if (value instanceof Double) {
			return "D";
		}
		if (value instanceof Byte) {
			return "X";
		}
		if (value instanceof Short) {
			return "S";
		}
		if (value instanceof Character) {
			return "C";
		}
		if (value instanceof Boolean) {
			return "B";
		}
		return "String";
	}

	private String getArrayFelixType(Object value) {
		Class<?> componentType = value.getClass().getComponentType();
		if (componentType == null) {
			return "String[]";
		}
		if (componentType.isPrimitive()) {
			componentType = switch (componentType.getName()) {
			case "int" -> Integer.class;
			case "long" -> Long.class;
			case "float" -> Float.class;
			case "double" -> Double.class;
			case "byte" -> Byte.class;
			case "short" -> Short.class;
			case "char" -> Character.class;
			case "boolean" -> Boolean.class;
			default -> String.class;
			};
		}
		return this.getFelixTypeFromClass(componentType) + "[]";
	}

	private String getFelixTypeFromClass(Class<?> type) {
		if (type == Integer.class) {
			return "I";
		}
		if (type == Long.class) {
			return "L";
		}
		if (type == Float.class) {
			return "F";
		}
		if (type == Double.class) {
			return "D";
		}
		if (type == Byte.class) {
			return "X";
		}
		if (type == Short.class) {
			return "S";
		}
		if (type == Character.class) {
			return "C";
		}
		if (type == Boolean.class) {
			return "B";
		}
		return "String";
	}

	private Object toTypedArray(String type, Object[] values) {
		return switch (type) {
		case "I[]" -> {
			final var result = new Integer[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Integer) values[i];
			}
			yield result;
		}
		case "L[]" -> {
			final var result = new Long[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Long) values[i];
			}
			yield result;
		}
		case "F[]" -> {
			final var result = new Float[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Float) values[i];
			}
			yield result;
		}
		case "D[]" -> {
			final var result = new Double[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Double) values[i];
			}
			yield result;
		}
		case "X[]" -> {
			final var result = new Byte[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Byte) values[i];
			}
			yield result;
		}
		case "S[]" -> {
			final var result = new Short[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Short) values[i];
			}
			yield result;
		}
		case "C[]" -> {
			final var result = new Character[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Character) values[i];
			}
			yield result;
		}
		case "B[]" -> {
			final var result = new Boolean[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = (Boolean) values[i];
			}
			yield result;
		}
		default -> {
			final var result = new String[values.length];
			for (int i = 0; i < values.length; i++) {
				result[i] = values[i] == null ? null : String.valueOf(values[i]);
			}
			yield result;
		}
		};
	}
}
