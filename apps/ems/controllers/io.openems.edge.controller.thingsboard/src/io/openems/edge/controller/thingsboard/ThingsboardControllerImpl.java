package io.openems.edge.controller.thingsboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.IoT.AT-Energy", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ThingsboardControllerImpl extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

	private static final String TB_TOPIC_GATEWAY_CONNECT = "v1/gateway/connect";
	private static final String TB_TOPIC_GATEWAY_DISCONNECT = "v1/gateway/disconnect";
	private static final String TB_TOPIC_GATEWAY_ATTRIBUTES = "v1/gateway/attributes";
	private static final String TB_TOPIC_GATEWAY_TELEMETRY = "v1/gateway/telemetry";

	private static final Path TREE_CONFIG_FILE = Paths.get(
			System.getProperty("user.home"), ".openems", "tree-config.json");

	private final Logger log = LoggerFactory.getLogger(ThingsboardControllerImpl.class);
	private final Set<String> connectedDevices = new HashSet<>();

	@Reference
	protected ComponentManager componentManager;

	private Config config;
	private IMqttAsyncClient mqttClient;
	private ScheduledExecutorService scheduler;
	private String connectedSiteName = null;
	private Channel<?> siteSerialChannel = null;
	private Consumer<?> siteSerialCallback = null;

	public ThingsboardControllerImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values());
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.connectMqtt();
		this.scheduler = Executors.newSingleThreadScheduledExecutor();
		this.scheduler.scheduleWithFixedDelay(this::scheduledPublish, //
				config.cycleTime(), config.cycleTime(), TimeUnit.SECONDS);
	}

	@Deactivate
	protected void deactivate() {
		if (this.scheduler != null) {
			this.scheduler.shutdownNow();
			this.scheduler = null;
		}
		this.unregisterSiteSerialListener();
		this.disconnectMqtt();
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		// Publishing is handled by the scheduler
	}

	private void scheduledPublish() {
		if (!this.config.enabled()) {
			return;
		}
		synchronized (this) {
			if (this.mqttClient == null || !this.mqttClient.isConnected()) {
				this.connectMqtt();
				return;
			}
			try {
				this.publishGatewaySnapshot();
			} catch (Exception e) {
				this.logError(this.log, "Scheduler publish error: " + e.getMessage());
			}
		}
	}

	private void publishGatewaySnapshot() {
		var siteName = this.resolveSiteName();
		if (siteName == null) {
			this.logWarn(this.log, "Cannot publish to ThingsBoard: Site serial number is not available");
			return;
		}

		var treeConfig = this.loadTreeConfig();
		var treeIndex = this.buildTreeIndex(treeConfig);

		var targetComponents = this.getTargetComponents();
		this.updateSiteSerialListener(targetComponents);
		this.ensureSiteConnected(siteName, targetComponents);

		// Push configuration tree as site attribute for cloud-local sync
		this.publish(TB_TOPIC_GATEWAY_ATTRIBUTES,
				ThingsboardPayloadHelper.buildSiteTreeAttributePayload(siteName, treeConfig).toString());

		long timestamp = System.currentTimeMillis();
		for (OpenemsComponent component : targetComponents) {
			if (ThingsboardPayloadHelper.isSiteComponent(component)) {
				// Merge channels into the site device (no separate ThingsBoard sub-device)
				this.publish(TB_TOPIC_GATEWAY_ATTRIBUTES,
						ThingsboardPayloadHelper.buildSiteComponentAttributesPayload(component, siteName).toString());
				var tel = ThingsboardPayloadHelper.buildSiteComponentTelemetryPayload(component, siteName, timestamp);
				if (tel != null) {
					this.publish(TB_TOPIC_GATEWAY_TELEMETRY, tel.toString());
				}
			} else {
				// Field device: publish as a separate ThingsBoard sub-device
				this.ensureComponentConnected(component, siteName);
				this.publish(TB_TOPIC_GATEWAY_ATTRIBUTES,
						ThingsboardPayloadHelper.buildComponentAttributesPayload(component, siteName, treeIndex).toString());
				var tel = ThingsboardPayloadHelper.buildTelemetryPayload(component, siteName, timestamp);
				if (tel != null) {
					this.publish(TB_TOPIC_GATEWAY_TELEMETRY, tel.toString());
				}
			}
		}
	}

	/**
	 * Reads tree-config.json from disk. Returns JsonNull on any error.
	 */
	private JsonElement loadTreeConfig() {
		try {
			if (Files.exists(TREE_CONFIG_FILE)) {
				return JsonParser.parseString(Files.readString(TREE_CONFIG_FILE));
			}
		} catch (IOException e) {
			this.logWarn(this.log, "Could not read tree-config.json: " + e.getMessage());
		}
		return JsonNull.INSTANCE;
	}

	/**
	 * Builds a flat map of componentId -> DeviceNode, enriched with project
	 * metadata for O(1) lookup during attribute publishing.
	 */
	private Map<String, JsonObject> buildTreeIndex(JsonElement treeConfig) {
		var index = new HashMap<String, JsonObject>();
		if (treeConfig == null || !treeConfig.isJsonObject()) {
			return index;
		}

		var project = treeConfig.getAsJsonObject();
		var projectName = this.getFirstString(project, "name", "projectCode", "companyName", "id");
		var devicesElement = project.get("devices");
		if (devicesElement == null || !devicesElement.isJsonArray()) {
			return index;
		}

		for (JsonElement deviceEl : devicesElement.getAsJsonArray()) {
			if (!deviceEl.isJsonObject()) {
				continue;
			}
			var device = deviceEl.getAsJsonObject();
			var componentId = this.getFirstString(device, "componentId");
			if (componentId.isEmpty()) {
				continue;
			}
			var node = device.deepCopy();
			if (!projectName.isEmpty()) {
				node.addProperty("projectName", projectName);
			}
			this.copyString(project, node, "projectCode");
			this.copyString(project, node, "companyName");
			this.copyString(project, node, "companyCode");
			this.copyString(project, node, "installationLocation");
			this.copyString(project, node, "companyAddress");
			this.copyString(device, node, "deviceType", "groupType");
			this.copyString(device, node, "name", "friendlyName");
			index.put(componentId, node);
		}
		return index;
	}

	private String getFirstString(JsonObject object, String... keys) {
		for (String key : keys) {
			if (!object.has(key) || object.get(key).isJsonNull()) {
				continue;
			}
			var value = object.get(key).getAsString().trim();
			if (!value.isEmpty()) {
				return value;
			}
		}
		return "";
	}

	private void copyString(JsonObject source, JsonObject target, String key) {
		this.copyString(source, target, key, key);
	}

	private void copyString(JsonObject source, JsonObject target, String sourceKey, String targetKey) {
		var value = this.getFirstString(source, sourceKey);
		if (!value.isEmpty()) {
			target.addProperty(targetKey, value);
		}
	}

	private List<OpenemsComponent> getTargetComponents() {
		List<OpenemsComponent> result = new ArrayList<>();
		for (OpenemsComponent component : this.componentManager.getEnabledComponents()) {
			if (ThingsboardPayloadHelper.isTargetComponent(component)) {
				result.add(component);
			}
		}
		return result;
	}

	private String resolveSiteName() {
		for (OpenemsComponent component : this.componentManager.getEnabledComponents()) {
			if ("_modem.maxicom".equalsIgnoreCase(component.id())) {
				return ThingsboardPayloadHelper.getChannelString(component, "SN");
			}
		}
		return null;
	}

	private void updateSiteSerialListener(List<OpenemsComponent> components) {
		for (OpenemsComponent component : components) {
			if (!"_modem.maxicom".equalsIgnoreCase(component.id())) {
				continue;
			}
			try {
				Channel<?> channel = component.channel("SN");
				if (channel == this.siteSerialChannel) {
					return;
				}
				this.unregisterSiteSerialListener();
				Consumer<?> callback = ignore -> this.publishGatewaySnapshot();
				this.registerSiteSerialListener(channel, callback);
				this.siteSerialChannel = channel;
				this.siteSerialCallback = callback;
			} catch (IllegalArgumentException e) {
				this.logWarn(this.log, "Unable to listen for _modem.maxicom/SN changes: " + e.getMessage());
			}
			return;
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void registerSiteSerialListener(Channel<?> channel, Consumer<?> callback) {
		((Channel) channel).onSetNextValue((Consumer) callback);
	}

	private void unregisterSiteSerialListener() {
		if (this.siteSerialChannel != null && this.siteSerialCallback != null) {
			this.siteSerialChannel.removeOnSetNextValueCallback(this.siteSerialCallback);
		}
		this.siteSerialChannel = null;
		this.siteSerialCallback = null;
	}

	private void ensureSiteConnected(String siteName, List<OpenemsComponent> targetComponents) {
		if (!siteName.equals(this.connectedSiteName)) {
			this.disconnectPreviousSiteDevices(this.connectedSiteName, targetComponents);
			this.connectedDevices.clear();
			this.connectedSiteName = siteName;
		}
		if (!this.connectedDevices.contains(siteName) && this.publish(TB_TOPIC_GATEWAY_CONNECT,
				ThingsboardPayloadHelper.buildConnectPayload(siteName, ThingsboardPayloadHelper.PROFILE_SITE).toString())) {
			this.connectedDevices.add(siteName);
			this.publish(TB_TOPIC_GATEWAY_ATTRIBUTES, ThingsboardPayloadHelper.buildSiteAttributesPayload(siteName).toString());
		}
	}

	private void disconnectPreviousSiteDevices(String previousSiteName, List<OpenemsComponent> targetComponents) {
		var deviceNames = new LinkedHashSet<String>();
		deviceNames.addAll(this.connectedDevices);

		if (previousSiteName != null) {
			for (OpenemsComponent component : targetComponents) {
				deviceNames.add(ThingsboardPayloadHelper.getDeviceName(component, previousSiteName));
			}
			deviceNames.add(previousSiteName);
		}

		for (String deviceName : deviceNames) {
			this.logInfo(this.log, "Disconnecting previous ThingsBoard gateway device: " + deviceName);
			this.publish(TB_TOPIC_GATEWAY_DISCONNECT, ThingsboardPayloadHelper.buildDisconnectPayload(deviceName).toString());
		}
	}

	private void ensureComponentConnected(OpenemsComponent component, String siteName) {
		var deviceName = ThingsboardPayloadHelper.getDeviceName(component, siteName);
		if (!this.connectedDevices.contains(deviceName) && this.publish(TB_TOPIC_GATEWAY_CONNECT, ThingsboardPayloadHelper
				.buildConnectPayload(deviceName, ThingsboardPayloadHelper.getProfile(component)).toString())) {
			this.connectedDevices.add(deviceName);
			// Full attributes (including tree metadata) are published each cycle in publishGatewaySnapshot
		}
	}

	private boolean publish(String topic, String payload) {
		try {
			if (this.mqttClient != null && this.mqttClient.isConnected()) {
				var message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
				message.setQos(1);
				message.setProperties(new MqttProperties());
				this.mqttClient.publish(topic, message);
				return true;
			}
		} catch (MqttException e) {
			this.logWarn(this.log, "MQTT Publish Error code " + e.getReasonCode() + ": " + e.getMessage());
			this.disconnectMqtt();
		}
		return false;
	}

	private synchronized void connectMqtt() {
		this.disconnectMqtt();
		try {
			String brokerUrl = "tcp://" + this.config.host() + ":" + this.config.port();
			String clientId = "OpenEMS_" + this.id();

			this.mqttClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
			this.mqttClient.setCallback(new MqttCallback() {
				@Override
				public void disconnected(MqttDisconnectResponse disconnectResponse) {
					String reason = (disconnectResponse != null) ? disconnectResponse.getReasonString() : "Unknown";
					ThingsboardControllerImpl.this.logWarn(ThingsboardControllerImpl.this.log,
							"MQTT Disconnected: " + reason);
				}

				@Override
				public void mqttErrorOccurred(MqttException exception) {
					ThingsboardControllerImpl.this.logError(ThingsboardControllerImpl.this.log,
							"MQTT Error: " + exception.getMessage());
				}

				@Override
				public void messageArrived(String topic, MqttMessage message) throws Exception {
					// unused
				}

				@Override
				public void deliveryComplete(IMqttToken token) {
					// unused
				}

				@Override
				public void connectComplete(boolean reconnect, String serverUri) {
					ThingsboardControllerImpl.this.logInfo(ThingsboardControllerImpl.this.log,
							"MQTT Connect Complete. Reconnect: " + reconnect);
				}

				@Override
				public void authPacketArrived(int reasonCode, MqttProperties properties) {
					// unused
				}
			});

			var options = new MqttConnectionOptions();
			options.setUserName(this.config.accessToken());
			options.setPassword("".getBytes(StandardCharsets.UTF_8));
			options.setAutomaticReconnect(false);
			options.setCleanStart(true);
			options.setConnectionTimeout(30);

			this.mqttClient.connect(options).waitForCompletion(30000);
			this.connectedDevices.clear();
			this.connectedSiteName = null;
			this.logInfo(this.log, "Connected to ThingsBoard (Gateway Mode)");

			this.publishGatewaySnapshot();
		} catch (MqttException e) {
			this.logError(this.log, "MQTT Connect Error [RC:" + e.getReasonCode() + "]: " + e.getMessage());
			this.log.error("MQTT Connection failed", e);
		} catch (Exception e) {
			this.logError(this.log, "MQTT Unexpected Error: " + e.getClass().getName() + " - " + e.getMessage());
		}
	}

	private synchronized void disconnectMqtt() {
		try {
			if (this.mqttClient != null) {
				if (this.mqttClient.isConnected()) {
					this.mqttClient.disconnect().waitForCompletion(5000);
				}
				this.mqttClient.close();
			}
		} catch (MqttException e) {
			// Ignored
		}
		this.mqttClient = null;
	}
}
