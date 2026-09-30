package io.openems.edge.controller.pvinverter.zeroexport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import io.openems.edge.controller.api.modbus.evn.ControllerApiModbusEvn;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * Controller for Active Power (P) control of all PV inverters.
 * 
 * <p>
 * This controller implements closed-loop control to make the grid connection
 * point (meter0) match the target setpoint from EVN or local configuration.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Detect which setpoint changed (Watt or Percent)</li>
 * <li>Convert Percent to Watt: targetW = percent/100 × totalSystemPower</li>
 * <li>Dynamic inverter handling: only control working inverters</li>
 * <li>Rate limiting to prevent oscillation</li>
 * </ul>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.PvInverter.ZeroExport", //
	        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvInverterZeroExportImpl extends AbstractOpenemsComponent
		implements ControllerPvInverterZeroExport, Controller, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(ControllerPvInverterZeroExportImpl.class);
    
	    private static final float EPSILON = 0.01f; // For float comparison

    @Reference
    private ComponentManager componentManager;

    private Config config;

    // All discovered inverters
    private List<ManagedSymmetricPvInverter> allInverters = new ArrayList<>();
    
    // Total system power (all inverters)
    private int totalSystemPowerW = 0;
    
    // Previous setpoints for change detection
    private float lastEvnPercent = 0f;
    private int lastEvnWatt = 0;
    
    // Track which mode is active
    private boolean usePercentMode = false;
    
    // Store previous limit per inverter ID (use Map for dynamic handling)
    private Map<String, Integer> previousLimits = new HashMap<>();
    
    // Discovery debounce
    private long lastDiscoveryTime = 0;
    
    // Robust Control State Variables
    private double pGridFilt = 0;
    private double pCmdFilt = 0;
    private double currentKp = 0.25; // Initial Gain
    private double lastError = 0;
    private int oscCounter = 0;
    // PI integral accumulator
    private double integralW = 0;
    private boolean integralInitialized = false;

	public ControllerPvInverterZeroExportImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
	                ControllerPvInverterZeroExport.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.currentKp = config.gainKp(); // Initialize limit
        
        // Discovery will happen in run() to avoid circular references during activation
        this.logInfo(this.log, "Activated with EVN controller: " + this.config.evn_id());
    }

    private void discoverInverters() {
        this.allInverters.clear();

        List<ManagedSymmetricPvInverter> smartLoggers = new ArrayList<>();
        List<ManagedSymmetricPvInverter> individualInverters = new ArrayList<>();

        for (OpenemsComponent comp : this.componentManager.getAllComponents()) {
            if (comp instanceof ManagedSymmetricPvInverter && comp.isEnabled()) {
                // Check for SmartLogger by ID prefix
                boolean isSmartLogger = comp.id().toLowerCase().startsWith("smartlogger");
                
                if (isSmartLogger) {
                     smartLoggers.add((ManagedSymmetricPvInverter) comp);
                } else {
                    individualInverters.add((ManagedSymmetricPvInverter) comp);
                }
            }
        }

        // Prioritize SmartLogger if available
        if (!smartLoggers.isEmpty()) {
            this.allInverters.addAll(smartLoggers);
        } else {
            this.allInverters.addAll(individualInverters);
        }

        // Sort by ID
        this.allInverters.sort((a, b) -> a.id().compareTo(b.id()));
        
        // Calculate total system power
        this.totalSystemPowerW = this.allInverters.stream()
                .mapToInt(inv -> inv.getMaxApparentPower().orElse(0))
                .sum();
        
        // Initialize previous limits for new inverters
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            if (!this.previousLimits.containsKey(inv.id())) {
                // New inverter - initialize with max power (no limit)
                int maxP = inv.getMaxApparentPower().orElse(0);
                this.previousLimits.put(inv.id(), maxP);
            }
        }
    }

    /**
     * Get list of currently working inverters.
     * An inverter is considered "working" if:
     * - It's enabled
     * - It has valid ActivePower reading (not null/undefined)
     * - No fault state (optional: can add more checks)
     */
    private List<ManagedSymmetricPvInverter> getWorkingInverters() {
        List<ManagedSymmetricPvInverter> working = new ArrayList<>();
        
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            // Check if inverter is responding (has valid power reading)
            if (inv.getActivePower().isDefined()) {
                working.add(inv);
            } else {
                this.logDebug(this.log, "Inverter " + inv.id() + " not responding, excluded from control");
            }
        }
        
        return working;
    }

    @Override
    @Deactivate
    protected void deactivate() {
        this.resetInverterLimits(this.allInverters);
        super.deactivate();
        this.integralW = 0;
        this.integralInitialized = false;
    }

    /**
     * Resets active power limits on all provided inverters.
     */
    private void resetInverterLimits(List<ManagedSymmetricPvInverter> inverters) {
        for (ManagedSymmetricPvInverter inv : inverters) {
            try {
                inv.setActivePowerLimit(null);
                this.previousLimits.put(inv.id(), inv.getMaxApparentPower().orElse(0));
            } catch (Exception e) {
                this.logError(this.log, "Error resetting inverter " + inv.id() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void run() throws OpenemsNamedException {
        // Refresh inverters periodically or on first run
        if (this.allInverters.isEmpty() || System.currentTimeMillis() - this.lastDiscoveryTime > 30000) {
            this.discoverInverters();
            this.lastDiscoveryTime = System.currentTimeMillis();
            
            this.logInfo(this.log, "Discovered " + this.allInverters.size() + " inverters"
                    + ", Total system power: " + this.totalSystemPowerW + "W");
        }

        // Get working inverters for this cycle
        List<ManagedSymmetricPvInverter> workingInverters = this.getWorkingInverters();
        
        if (workingInverters.isEmpty()) {
            this.logWarn(this.log, "No working PV inverters found");
			this.applyFailSafeLimit(this.allInverters);
            return;
        }

        // Determine target setpoint
        int targetGridPowerW = 0;
        boolean evnEnabled = false;
        boolean hasValidSetpoint = false;

        // Check EVN control
        if (this.config.allowEvnControl()) {
            try {
                ControllerApiModbusEvn evn = this.componentManager.getComponent(this.config.evn_id());
                evnEnabled = evn.getPOutEnabled().orElse(false);

                if (evnEnabled) {
                    float evnPercent = evn.getPOutSetpointPercent().orElse(0f);
                    int evnWatt = evn.getPOutSetpointWatt().orElse(0);

                    // Detect which setpoint changed (use epsilon for float)
                    boolean percentChanged = Math.abs(evnPercent - this.lastEvnPercent) > EPSILON;
                    boolean wattChanged = (evnWatt != this.lastEvnWatt);
                    
	                    if (percentChanged && !wattChanged) {
                        this.usePercentMode = true;
                    } else if (wattChanged && !percentChanged) {
                        this.usePercentMode = false;
                    } else if (percentChanged && wattChanged) {
                        // Both changed → prefer non-zero
                        this.usePercentMode = (evnPercent != 0 && evnWatt == 0);
                    }
                    
                    // Update last values
                    this.lastEvnPercent = evnPercent;
                    this.lastEvnWatt = evnWatt;
                    
					hasValidSetpoint = true;
					if (this.usePercentMode) {
						float boundedPercent = Math.max(0f, Math.min(evnPercent, 100f));
						targetGridPowerW = -(int) (boundedPercent / 100f * this.totalSystemPowerW);
					} else {
						targetGridPowerW = -Math.max(0, Math.min(evnWatt, this.totalSystemPowerW));
					}
                }
            } catch (OpenemsNamedException e) {
                this.logDebug(this.log, "EVN controller not found, using local control");
            }

		}

        // Local control mode (fallback)
	        if (!evnEnabled || !hasValidSetpoint) {
	            hasValidSetpoint = true;
	            if (this.config.usePercentage()) {
	                int boundedPercent = Math.max(0, Math.min(this.config.powerLimitPercent(), 100));
	                targetGridPowerW = -(int) (boundedPercent / 100f * this.totalSystemPowerW);
	            } else {
	                targetGridPowerW = -Math.max(0, Math.min(this.config.powerLimit(), this.totalSystemPowerW));
	            }
        }

        if (!hasValidSetpoint) {
            return; // No control needed
        }

        // Apply closed-loop control
        this.applyClosedLoopControl(targetGridPowerW, workingInverters);
    }

    /**
     * Closed-loop control to match grid power to setpoint.
     */
    private void applyClosedLoopControl(int targetGridPowerW, List<ManagedSymmetricPvInverter> workingInverters) 
            throws OpenemsNamedException {
        
        // --- 1. Get Feedback (Grid Meter) ---
		ElectricityMeter meter;
		try {
			OpenemsComponent meterComp = this.componentManager.getComponent(this.config.meter_id());
			if (!(meterComp instanceof ElectricityMeter electricityMeter)) {
				this.applyFailSafeLimit(workingInverters);
				return;
			}
			meter = electricityMeter;
		} catch (OpenemsNamedException e) {
			this.applyFailSafeLimit(workingInverters);
			return;
		}

		var gridPower = meter.getActivePower();
		if (!gridPower.isDefined()) {
			this.applyFailSafeLimit(workingInverters);
			return;
		}
		int actualGridPowerRaw = gridPower.get();

        // --- 2. Input Filtering (Exponential Smoothing) ---
        double alphaIn = this.config.filterAlpha();
        if (this.pGridFilt == 0) this.pGridFilt = actualGridPowerRaw; // Init
        this.pGridFilt = (alphaIn * actualGridPowerRaw) + ((1 - alphaIn) * this.pGridFilt);

        // --- 3. Error Calculation ---
		int errorW = (int) this.pGridFilt - targetGridPowerW;

        // Calculate System Capacity and Thresholds
        int totalCapacityW = this.totalSystemPowerW;
        if (totalCapacityW == 0) totalCapacityW = 100000; // Default 100kW prevention
        
        int deadbandW = (int)(totalCapacityW * this.config.deadbandPercent() / 100.0);
        if (deadbandW < 1000) deadbandW = 1000; // Min 1kW

        // Apply Deadband
        if (Math.abs(errorW) < deadbandW) {
            errorW = 0;
            // Decay oscillation counter when stable
            this.oscCounter = Math.max(0, this.oscCounter - 1);
        }

        // --- 4. Adaptive Gain Scheduling ---
        // Detect Oscillation: Error sign flip AND significant magnitude
        boolean isOscillating = (Math.signum(errorW) != Math.signum(this.lastError)) 
                             && (Math.abs(errorW) > deadbandW);
        
        double kpNom = this.config.gainKp();
        double kpMin = 0.05;

        if (isOscillating) {
            this.oscCounter++;
            if (this.oscCounter >= 3) {
                 // Reduce gain by 25%
                 this.currentKp = Math.max(kpMin, this.currentKp * 0.75);
                 this.oscCounter = 0; // Reset penalty
                 this.logInfo(this.log, "Oscillation detected. Reduced Kp to: " + String.format("%.3f", this.currentKp));
            }
        } else {
            // Slowly recover gain
            this.currentKp = Math.min(kpNom, this.currentKp + 0.001);
            if(errorW == 0) this.oscCounter = 0;
        }
        this.lastError = errorW;

        // --- 5. PI Control with back-calculation anti-windup ---
        final double TS = 1.0; // Sample time [s], OpenEMS default cycle = 1 s

        // Initialize integral on first run to current measured output (bumpless start)
        int totalMeasuredInverterPower = workingInverters.stream()
                .mapToInt(inv -> inv.getActivePower().orElse(0))
                .sum();
        if (!this.integralInitialized) {
            this.integralW = totalMeasuredInverterPower;
            this.integralInitialized = true;
        }

        // PI unsaturated output = desired total system power [W]
        double uPI = (this.currentKp * errorW) + this.integralW;

        // --- 6. Clamp PI output (saturation) ---
        int maxRampW = (int)(totalCapacityW * this.config.maxRampRatePercent() / 100.0);
        double uSat = Math.max(0, Math.min(uPI, totalCapacityW));

        // Ramp limiting on top of saturation
        double prevCmd = this.pCmdFilt == 0 ? totalMeasuredInverterPower : this.pCmdFilt;
        uSat = Math.min(Math.max(uSat, prevCmd - maxRampW), prevCmd + maxRampW);
        uSat = Math.max(0, Math.min(uSat, totalCapacityW));

        // --- 7. Back-calculation anti-windup integral update ---
        // integral += Ki*Ts*e + Kaw*Ts*(uSat - uPI)
        this.integralW += this.config.gainKi() * TS * errorW
                        + this.config.gainKaw() * TS * (uSat - uPI);
        // Clamp integral itself to prevent windup beyond capacity
        this.integralW = Math.max(0, Math.min(this.integralW, totalCapacityW));

        // --- 8. Output Smoothing ---
        double alphaOut = this.config.outputSmoothing();
        if (this.pCmdFilt == 0) this.pCmdFilt = uSat;
        this.pCmdFilt = (alphaOut * uSat) + ((1 - alphaOut) * this.pCmdFilt);

        int finalTargetSystemPower = (int) this.pCmdFilt;
        finalTargetSystemPower = Math.max(0, Math.min(finalTargetSystemPower, totalCapacityW));

        // --- 9. Distribute to Inverters ---
        // Use ratio based on capacity
        int totalWorkingCapacityW = workingInverters.stream()
                .mapToInt(inv -> inv.getMaxApparentPower().orElse(0))
                .sum();
        
        if (totalWorkingCapacityW <= 0) {
			this.applyFailSafeLimit(workingInverters);
			return;
		}

        for (ManagedSymmetricPvInverter inv : workingInverters) {
            int maxP = inv.getMaxApparentPower().orElse(0);
            if (maxP <= 0) continue;

            double ratio = (double) maxP / totalWorkingCapacityW;
            int invTarget = (int)(finalTargetSystemPower * ratio);
            
            // Clamp individual
            invTarget = Math.max(0, Math.min(invTarget, maxP));

            try {
                inv.setActivePowerLimit(invTarget);
                this.previousLimits.put(inv.id(), invTarget);
            } catch (Exception e) {
                 this.logError(this.log, "Error setting limit: " + e.getMessage());
            }
        }

        this.logDebug(this.log, String.format(
                "PICtrl: Err=%dW, uPI=%.0f, uSat=%.0f, Int=%.0f, Kp=%.3f, Meas=%dW, Cmd=%dW",
                errorW, uPI, uSat, this.integralW, this.currentKp, totalMeasuredInverterPower, finalTargetSystemPower));
    }

	private void applyFailSafeLimit(List<ManagedSymmetricPvInverter> workingInverters) {
		this.pGridFilt = 0;
		this.pCmdFilt = 0;
		for (ManagedSymmetricPvInverter inverter : workingInverters) {
			try {
				inverter.setActivePowerLimit(0);
				this.previousLimits.put(inverter.id(), 0);
			} catch (OpenemsNamedException e) {
				this.logError(this.log, "Unable to apply zero-export fail-safe to " + inverter.id() + ": "
						+ e.getMessage());
			}
		}
	}
}
