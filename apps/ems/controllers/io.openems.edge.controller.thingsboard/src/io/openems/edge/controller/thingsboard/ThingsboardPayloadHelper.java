package io.openems.edge.controller.thingsboard;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.OpenemsComponent;

final class ThingsboardPayloadHelper {

	static final String PROFILE_SITE = "Site";

	private static final Set<String> ATTRIBUTE_CHANNELS = Set.of(//
			"Name", //
			"Fw", //
			"SN", //
			"Model", //
			"PN", //
			"NumberPv", //
			"NumberMppt", //
			"RatePower", //
			"Address", //
			"Baudrate", //
			"Parity", //
			"SerialNumber", //
			"HWVersion", //
			"SWVersion", //
			"TagNumber" //
	);

	private ThingsboardPayloadHelper() {
	}

	static boolean isTargetComponent(OpenemsComponent component) {
		var id = component.id().toLowerCase(Locale.ROOT);
		return id.startsWith("meter") //
				|| id.startsWith("pvinverter") //
				|| id.startsWith("smartlogger") //
				|| id.startsWith("_data") //
				|| id.equals("_modem.maxicom") //
				|| id.startsWith("evn") //
				|| id.equals("_sum")
				|| id.startsWith("wika");
	}

	/**
	 * Returns true for components whose channels should be merged into the site
	 * device instead of published as a separate ThingsBoard sub-device.
	 * Covers: _modem.maxicom (modem info), _data (data logger), _sum (system KPIs).
	 */
	static boolean isSiteComponent(OpenemsComponent component) {
		var id = component.id().toLowerCase(Locale.ROOT);
		return id.equals("_modem.maxicom") //
				|| id.startsWith("_data") //
				|| id.equals("_sum");
	}

	static String getProfile(OpenemsComponent component) {
		var id = component.id().toLowerCase(Locale.ROOT);
		if (id.startsWith("meter")) {
			return "Meter";
		}
		if (id.startsWith("pvinverter")) {
			return isHybridPvInverter(component) ? "HybridInverter" : "Inverter";
		}
		if (id.startsWith("smartlogger")) {
			return "Logger";
		}
		if (id.startsWith("wika")){
			return "Wika";
		}
		// Maxicom group: _data, _modem.maxicom, evn
		return "Maxicom";
	}

	static boolean isAttributeChannel(Channel<?> channel) {
		var channelId = channel.channelId().id();
		return channelId.startsWith("_Property") || ATTRIBUTE_CHANNELS.contains(channelId);
	}

	static String getDeviceName(OpenemsComponent component, String siteName) {
		var alias = component.id();
		return (siteName.trim() + " " + alias.trim()).trim();
	}

	static JsonObject buildConnectPayload(String deviceName, String profile) {
		var payload = new JsonObject();
		payload.addProperty("device", deviceName);
		payload.addProperty("type", profile);
		return payload;
	}

	static JsonObject buildDisconnectPayload(String deviceName) {
		var payload = new JsonObject();
		payload.addProperty("device", deviceName);
		return payload;
	}

	static JsonObject buildSiteAttributesPayload(String siteName) {
		var attributes = new JsonObject();
		attributes.addProperty("serialNumber", siteName);
		attributes.addProperty("componentType", PROFILE_SITE);

		var payload = new JsonObject();
		payload.add(siteName, attributes);
		return payload;
	}

	/**
	 * Builds a site attributes payload that includes the full configuration tree
	 * (Project → DeviceGroup → Device) for cloud-local synchronisation.
	 * The tree is serialised as a JSON string under the key "treeConfig".
	 */
	static JsonObject buildSiteTreeAttributePayload(String siteName, JsonElement treeConfig) {
		var attributes = new JsonObject();
		attributes.addProperty("treeConfig", treeConfig.toString());

		var payload = new JsonObject();
		payload.add(siteName, attributes);
		return payload;
	}

	/**
	 * Enriches a component attributes object with flat tree metadata looked up by
	 * componentId from the tree index.
	 *
	 * @param attributes component attributes JsonObject to enrich in-place
	 * @param componentId the OpenEMS component id (e.g. "meter0")
	 * @param treeIndex   map from componentId → DeviceNode JsonObject
	 */
	static void enrichWithTreeInfo(JsonObject attributes, String componentId,
			Map<String, JsonObject> treeIndex) {
		var node = treeIndex.get(componentId);
		if (node == null) {
			return;
		}
		if (node.has("groupName")) {
			attributes.addProperty("groupName", node.get("groupName").getAsString());
		}
		if (node.has("groupType")) {
			attributes.addProperty("groupType", node.get("groupType").getAsString());
		}
		if (node.has("projectName")) {
			attributes.addProperty("projectName", node.get("projectName").getAsString());
		}
		if (node.has("friendlyName")) {
			attributes.addProperty("friendlyName", node.get("friendlyName").getAsString());
		}
		if (node.has("projectCode")) {
			attributes.addProperty("projectCode", node.get("projectCode").getAsString());
		}
		if (node.has("companyName")) {
			attributes.addProperty("companyName", node.get("companyName").getAsString());
		}
		if (node.has("companyCode")) {
			attributes.addProperty("companyCode", node.get("companyCode").getAsString());
		}
		if (node.has("installationLocation")) {
			attributes.addProperty("installationLocation", node.get("installationLocation").getAsString());
		}
		if (node.has("companyAddress")) {
			attributes.addProperty("companyAddress", node.get("companyAddress").getAsString());
		}
	}

	/**
	 * Builds an attributes payload that targets the site device directly (key =
	 * siteName). Used for site components (_modem.maxicom, _data, _sum) whose
	 * channels are merged into the site device rather than a sub-device.
	 */
	static JsonObject buildSiteComponentAttributesPayload(OpenemsComponent component, String siteName) {
		var attributes = new JsonObject();
		for (Channel<?> channel : component.channels()) {
			if (!isReadableWithValue(channel) || !isAttributeChannel(channel)) {
				continue;
			}
			addProperty(attributes, channel.channelId().id(), getPayloadValue(channel));
		}
		var payload = new JsonObject();
		payload.add(siteName, attributes);
		return payload;
	}

	/**
	 * Builds a telemetry payload that targets the site device directly (key =
	 * siteName). Used for site components (_modem.maxicom, _data, _sum).
	 * Returns null when there are no telemetry values to publish.
	 */
	static JsonObject buildSiteComponentTelemetryPayload(OpenemsComponent component, String siteName,
			long timestamp) {
		var values = new JsonObject();
		for (Channel<?> channel : component.channels()) {
			if (!isReadableWithValue(channel) || isAttributeChannel(channel)) {
				continue;
			}
			addProperty(values, channel.channelId().id(), getPayloadValue(channel));
		}
		if (values.size() == 0) {
			return null;
		}
		var sample = new JsonObject();
		sample.addProperty("ts", timestamp);
		sample.add("values", values);
		var samples = new JsonArray();
		samples.add(sample);
		var payload = new JsonObject();
		payload.add(siteName, samples);
		return payload;
	}

	static JsonObject buildComponentAttributesPayload(OpenemsComponent component, String siteName,
			Map<String, JsonObject> treeIndex) {
		var deviceName = getDeviceName(component, siteName);
		var profile = getProfile(component);
		var attributes = new JsonObject();
		attributes.addProperty("parent", siteName);
		attributes.addProperty("site", siteName);
		attributes.addProperty("deviceName", deviceName);
		attributes.addProperty("componentId", component.id());
		attributes.addProperty("componentAlias", component.alias());
		attributes.addProperty("componentType", profile);

		// Enrich with tree metadata if available
		enrichWithTreeInfo(attributes, component.id(), treeIndex);

		for (Channel<?> channel : component.channels()) {
			if (!isReadableWithValue(channel) || !isAttributeChannel(channel)) {
				continue;
			}
			var value = getPayloadValue(channel);
			addProperty(attributes, channel.channelId().id(), value);
		}

		var payload = new JsonObject();
		payload.add(deviceName, attributes);
		return payload;
	}

	static JsonObject buildTelemetryPayload(OpenemsComponent component, String siteName, long timestamp) {
		var values = new JsonObject();

		for (Channel<?> channel : component.channels()) {
			if (!isReadableWithValue(channel) || isAttributeChannel(channel)) {
				continue;
			}
			var value = getPayloadValue(channel);
			addProperty(values, channel.channelId().id(), value);
		}

		if (values.size() == 0) {
			return null;
		}

		var sample = new JsonObject();
		sample.addProperty("ts", timestamp);
		sample.add("values", values);

		var samples = new JsonArray();
		samples.add(sample);

		var payload = new JsonObject();
		payload.add(getDeviceName(component, siteName), samples);
		return payload;
	}

	static String getChannelString(OpenemsComponent component, String channelId) {
		try {
			var channel = component.channel(channelId);
			var value = getChannelValue(channel);
			if (value == null) {
				return null;
			}
			var result = value.toString().trim();
			return result.isEmpty() ? null : result;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static Object convertUnitValue(Object value, Unit unit) {
		if (value == null || unit == null || !(value instanceof Number)) {
			return value;
		}

		double numValue = ((Number) value).doubleValue();
		switch (unit) {
		case MILLIAMPERE:
		case MILLIVOLT:
		case MILLIWATT:
		case MILLIHERTZ:
		case MILLIBAR:
		case MILLIOHM:
		case MILLIAMPERE_HOURS:
		case MILLISECONDS:
			return numValue / 1000.0;

		case MICROAMPERE:
		case MICROVOLT:
		case MICROOHM:
			return numValue / 1000000.0;

		case DEZIAMPERE:
		case DEZIVOLT:
		case DEZIDEGREE_CELSIUS:
			return numValue / 10.0;

		case WATT:
		case VOLT_AMPERE:
		case VOLT_AMPERE_REACTIVE:
		case WATT_HOURS:
		case VOLT_AMPERE_HOURS:
		case VOLT_AMPERE_REACTIVE_HOURS:
		case CUMULATED_WATT_HOURS:
		case AMPERE_HOURS:
		case OHM:
			return numValue / 1000.0;

		default:
			return value;
		}
	}

	private static boolean isReadableWithValue(Channel<?> channel) {
		if (channel.channelId().doc().getAccessMode() == AccessMode.WRITE_ONLY) {
			return false;
		}
		return getChannelValue(channel) != null || isDiagnosticChannel(channel);
	}

	private static Object getPayloadValue(Channel<?> channel) {
		var value = getChannelValue(channel);
		if (value == null && isDiagnosticChannel(channel)) {
			return channel.getNextValue().asString();
		}

		var valueObject = channel.value().isDefined() ? channel.value() : channel.getNextValue();
		var optionString = valueObject.asOptionString();
		if (!optionString.isEmpty()) {
			return optionString;
		}
		return convertUnitValue(value, channel.channelId().doc().getUnit());
	}

	private static Object getChannelValue(Channel<?> channel) {
		var activeValue = channel.value().get();
		if (activeValue != null) {
			return activeValue;
		}
		return channel.getNextValue().get();
	}

	private static boolean isDiagnosticChannel(Channel<?> channel) {
		var channelId = channel.channelId().id();
		return channelId.equals("State") || channelId.endsWith("Failed");
	}

	private static boolean isHybridPvInverter(OpenemsComponent component) {
		var className = component.getClass().getName().toLowerCase(Locale.ROOT);
		return className.contains(".hybrid.") || className.contains("hybrid");
	}

	private static void addProperty(JsonObject object, String key, Object value) {
		if (value == null) {
			return;
		}
		if (value instanceof Number) {
			object.addProperty(key, (Number) value);
		} else if (value instanceof Boolean) {
			object.addProperty(key, (Boolean) value);
		} else if (value instanceof Character) {
			object.addProperty(key, (Character) value);
		} else {
			object.addProperty(key, value.toString());
		}
	}
}
