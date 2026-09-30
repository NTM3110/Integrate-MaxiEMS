package io.openems.edge.controller.pvinverter.powerlimit;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.common.channel.WriteChannel;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.PvInverter.PowerLimit", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvInverterFixPowerLimitImpl extends AbstractOpenemsComponent
		implements ControllerPvInverterFixPowerLimit, Controller, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(ControllerPvInverterFixPowerLimitImpl.class);

	@Reference
	private ComponentManager componentManager;

	/** Use percentage mode if true, absolute Watt mode if false. */
	private boolean usePercentage = false;
	/** The configured Power Limit in Watts. */
	private int powerLimit = 0;
	/** The configured Power Limit in percentage (0-100). */
	private int powerLimitPercent = 100;

	// State for currently active limits
	private final java.util.List<String> activeDevices = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final java.util.Map<String, Integer> activeLimits = new java.util.concurrent.ConcurrentHashMap<>();

	// Store last received commands for partial updates
	private String deviceListCmd = null;
	private String limitListCmd = null;

	public ControllerPvInverterFixPowerLimitImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerPvInverterFixPowerLimit.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;

		this.usePercentage = config.usePercentage();
		this.powerLimit = config.powerLimit();
		this.powerLimitPercent = config.powerLimitPercent();

		// Initialize with static config
		if (config.pvInverter_id() != null && !config.pvInverter_id().trim().isEmpty()) {
			this.activeDevices.add(config.pvInverter_id());
			int limitW = this.usePercentage ? 0 : this.powerLimit; // If percentage, W is 0 (logic handled in apply)
			// For static config, we store W if not percentage. If percentage, we handle differently?
			// To simplify: The dynamic logic assumes W (kW input). 
			// The static logic has 'usePercentage'. 
			// Let's store the static intent.
			// Actually, to mix both, let's keep separate "static" fields and "dynamic" state?
			// Or just initialize "activeLimits" with the static value?
			// If usePercentage is TRUE, the static logic effectively sets % not W.
			// But dynamic is ALWAYS W.
			// Let's settle on: activeLimits stores Watts.
			// If static config is Percentage, we can't easily map to Watts without knowing Max Power.
			// So let's keep static handling separate or just say "Config is fallback".
			// Use fallback approach:
			// If activeDevices is EMPTY, try Config.
			// But Activate adds to activeDevices? 
			// Let's NOT add static config to activeDevices/Limits to avoid confusion.
			// We only use activeObjects for Dynamic commands.
		}

		this.logInfo(this.log, "Configured: " + (this.usePercentage
				? this.powerLimitPercent + "%"
				: this.powerLimit + " W"));
	}

	@Override
	@Deactivate
	protected void deactivate() {
		// Reset dynamic limits
		for (String deviceId : this.activeDevices) {
			this.resetLimit(deviceId);
		}
		
		// Reset static limit
		String staticId = this.config().pvInverter_id();
		if (staticId != null && !staticId.isEmpty() && !this.activeDevices.contains(staticId)) {
			this.resetLimit(staticId);
		}

		super.deactivate();
	}

	private void resetLimit(String deviceId) {
		try {
			ManagedSymmetricPvInverter pvInverter = this.componentManager.getComponent(deviceId);
			pvInverter.setActivePowerLimit(null);
			pvInverter.setActivePowerLimitPercent(null);
		} catch (OpenemsNamedException e) {
			// ignore
		}
	}

	@Override
	public void run() throws OpenemsNamedException {
		// 1. Check for new commands
		String newDeviceListCmd = ((WriteChannel<String>) this.channel(ControllerPvInverterFixPowerLimit.ChannelId.DEVICE))
				.getNextWriteValueAndReset().orElse(null);
		String newLimitListCmd = ((WriteChannel<String>) this.channel(ControllerPvInverterFixPowerLimit.ChannelId.LIMIT))
				.getNextWriteValueAndReset().orElse(null);

		boolean configChanged = false;
		if (newDeviceListCmd != null) {
			this.deviceListCmd = newDeviceListCmd;
			configChanged = true;
		}
		if (newLimitListCmd != null) {
			this.limitListCmd = newLimitListCmd;
			configChanged = true;
		}

		if (configChanged && this.deviceListCmd != null && this.limitListCmd != null) {
			this.updateActiveLimits(this.deviceListCmd, this.limitListCmd);
		}

		// 2. Apply Dynamic Limits
		if (!this.activeDevices.isEmpty()) {
			for (String deviceId : this.activeDevices) {
				Integer limitW = this.activeLimits.get(deviceId);
				if (limitW != null) {
					this.applyLimit(deviceId, limitW);
				}
			}
		} else {
			// 3. Fallback to Static Config
			this.applyStaticLimit();
		}
	}

	private void updateActiveLimits(String deviceList, String limitList) {
		// Clear previous state? User requested "Modify ... to realize".
		// Usually new command set replaces old one.
		this.activeDevices.clear();
		this.activeLimits.clear();

		String[] devices = deviceList.replaceAll("[{}]", "").split("[;,]");
		String[] limits = limitList.replaceAll("[{}]", "").split(";");

		for (int i = 0; i < devices.length; i++) {
			String deviceId = devices[i].trim();
			if (deviceId.isEmpty()) {
				continue;
			}
			if (i >= limits.length) {
				break;
			}
			String limitStr = limits[i].trim().replace(",", ".");
			try {
				float limitKw = Float.parseFloat(limitStr);
				int limitW = (int) (limitKw * 1000);
				
				this.activeDevices.add(deviceId);
				this.activeLimits.put(deviceId, limitW);
			} catch (NumberFormatException e) {
				this.logError(this.log, "Unable to parse limit for " + deviceId + ": " + limitStr);
			}
		}
	}

	private void applyLimit(String deviceId, int limitWatts) {
		try {
			ManagedSymmetricPvInverter pvInverter = this.componentManager.getComponent(deviceId);
			pvInverter.setActivePowerLimit(limitWatts);
			// Trigger channel update if needed? Usually implicit.
		} catch (OpenemsNamedException e) {
			this.logError(this.log, "Failed to set limit for " + deviceId + ": " + e.getMessage());
		}
	}

	private void applyStaticLimit() throws OpenemsNamedException {
		String id = this.config().pvInverter_id();
		if (id == null || id.isEmpty()) {
			return;
		}
		ManagedSymmetricPvInverter pvInverter = this.componentManager.getComponent(id);
		if (this.usePercentage) {
			pvInverter.setActivePowerLimitPercent(this.powerLimitPercent);
		} else {
			pvInverter.setActivePowerLimit(this.powerLimit);
		}
	}
	
	private Config config() {
		// Helper to get config if needed, though we stored values in activate.
		// We stored id/alias/enabled in super. But specific config fields we stored in fields.
		// However, we didn't store the static ID. Let's fix activate to store it or access it.
		// Accessing via a field is better.
		return this.config;
	}
	
	private Config config; // Store config ref

}

