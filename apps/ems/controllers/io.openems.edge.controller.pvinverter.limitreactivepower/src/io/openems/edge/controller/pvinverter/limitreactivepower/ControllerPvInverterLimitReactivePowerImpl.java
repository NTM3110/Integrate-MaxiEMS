package io.openems.edge.controller.pvinverter.limitreactivepower;

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
 * Controller for Reactive Power (Q) control of all PV inverters.
 * 
 * <p>
 * This controller implements closed-loop control to make the grid connection
 * point (meter0) match the target setpoint from EVN or local configuration.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Detect which setpoint changed (Var or Percent)</li>
 * <li>Convert Percent to Var: targetVar = percent/100 × totalSystemPower</li>
 * <li>Dynamic inverter handling: only control working inverters</li>
 * <li>Rate limiting to prevent oscillation</li>
 * <li>Supports positive (inductive) and negative (capacitive) reactive power</li>
 * </ul>
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.PvInverter.LimitReactivePower", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvInverterLimitReactivePowerImpl extends AbstractOpenemsComponent
        implements ControllerPvInverterLimitReactivePower, Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ControllerPvInverterLimitReactivePowerImpl.class);

    private static final String PVINVERTER_ID_PREFIX = "pvInverter";
    private static final String DEFAULT_METER_ID = "meter0";
    
    // Control parameters
    private static final int DEADBAND_VAR = 10000;
    private static final float EPSILON = 0.01f; // For float comparison

    @Reference
    private ComponentManager componentManager;

    private Config config;

    // All discovered inverters
    private List<ManagedSymmetricPvInverter> allInverters = new ArrayList<>();
    
    // Total system reactive power capacity
    private int totalSystemPowerVar = 0;
    
    // Previous setpoints for change detection
    private float lastEvnPercent = 0f;
    private int lastEvnVar = 0;
    
    // Track which mode is active
    private boolean usePercentMode = false;
    
    // Store previous limit per inverter ID (use Map for dynamic handling)
    private Map<String, Integer> previousLimits = new HashMap<>();
    
    // Discovery debounce
    private long lastDiscoveryTime = 0;
    
    // Flag to indicate if EVN has sent any command
    private boolean evnCommandReceived = false;

    // Robust Control State Variables
    private double qGridFilt = 0;
    private double qCmdFilt = 0;
    private double currentKp = 0.25;
    private double lastError = 0;
    private int oscCounter = 0;

    public ControllerPvInverterLimitReactivePowerImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerPvInverterLimitReactivePower.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;
        this.currentKp = config.gainKp();

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
        
        // Calculate total system reactive power
        this.totalSystemPowerVar = this.allInverters.stream()
                .mapToInt(inv -> inv.getMaxReactivePower().orElse(0))
                .sum();
        
        // If no max reactive power defined, estimate as 50% of apparent power
        if (this.totalSystemPowerVar == 0) {
            this.totalSystemPowerVar = this.allInverters.stream()
                    .mapToInt(inv -> (int)(inv.getMaxApparentPower().orElse(0) * 0.5))
                    .sum();
        }
        
        // Initialize previous limits for new inverters (default 0 for Q)
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            if (!this.previousLimits.containsKey(inv.id())) {
                this.previousLimits.put(inv.id(), 0);
            }
        }
    }

    /**
     * Get list of currently working inverters.
     */
    private List<ManagedSymmetricPvInverter> getWorkingInverters() {
        List<ManagedSymmetricPvInverter> working = new ArrayList<>();
        
        for (ManagedSymmetricPvInverter inv : this.allInverters) {
            // Check if inverter is responding (has valid power reading)
            if (inv.getActivePower().isDefined()) {
                working.add(inv);
            } else {
                this.logDebug(this.log, "Inverter " + inv.id() + " not responding, excluded from Q control");
            }
        }
        
        return working;
    }

    @Override
    @Deactivate
    protected void deactivate() {
        this.resetInverterLimits(this.allInverters);
        super.deactivate();
    }

    /**
     * Resets reactive power limits on all provided inverters.
     */
    private void resetInverterLimits(List<ManagedSymmetricPvInverter> inverters) {
        for (ManagedSymmetricPvInverter inv : inverters) {
            try {
                inv.setReactivePowerLimit(null);
                this.previousLimits.put(inv.id(), 0);
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
                    + ", Total system Q power: " + this.totalSystemPowerVar + "var");
        }

        // Get working inverters for this cycle
        List<ManagedSymmetricPvInverter> workingInverters = this.getWorkingInverters();
        
        if (workingInverters.isEmpty()) {
            this.logWarn(this.log, "No working PV inverters found");
            return;
        }

        // Determine target setpoint
        int targetGridPowerVar = 0;
        boolean evnEnabled = false;
        boolean hasValidSetpoint = false;

        // Check EVN control
        if (this.config.allowEvnControl()) {
            try {
                ControllerApiModbusEvn evn = this.componentManager.getComponent(this.config.evn_id());
                evnEnabled = evn.getQOutEnabled().orElse(false);

                if (evnEnabled) {
                    float evnPercent = evn.getQOutSetpointPercent().orElse(0f);
                    int evnVar = evn.getQOutSetpointVar().orElse(0);

                    // Detect which setpoint changed
                    boolean percentChanged = Math.abs(evnPercent - this.lastEvnPercent) > EPSILON;
                    boolean varChanged = (evnVar != this.lastEvnVar);
                    
                    if (percentChanged || varChanged) {
                        this.evnCommandReceived = true;
                    }
                    
                    if (percentChanged && !varChanged) {
                        this.usePercentMode = true;
                    } else if (varChanged && !percentChanged) {
                        this.usePercentMode = false;
                    } else if (percentChanged && varChanged) {
                        this.usePercentMode = (evnPercent != 0 && evnVar == 0);
                    }
                    
                    this.lastEvnPercent = evnPercent;
                    this.lastEvnVar = evnVar;
                    
                    // Check if we have valid setpoint (Q can be 0 as valid target)
                    if (evnPercent != 0 || evnVar != 0 || this.evnCommandReceived) {
                        hasValidSetpoint = true;
                        
                        if (this.usePercentMode) {
                            targetGridPowerVar = (int) (evnPercent / 100f * this.totalSystemPowerVar);
                        } else {
                            targetGridPowerVar = evnVar;
                        }
                    }
                }
            } catch (OpenemsNamedException e) {
                this.logDebug(this.log, "EVN controller not found, using local control");
            }

            // [FIX] If EVN control is allowed but NOT enabled via Modbus, stay idle
            if (!evnEnabled) {
                this.logDebug(this.log, "EVN Control allowed but Q-out is DISABLED. Staying idle.");
                this.resetInverterLimits(workingInverters);
                return;
            }
        }

        // Local control mode (fallback)
        if (!evnEnabled || !hasValidSetpoint) {
            hasValidSetpoint = true;
            if (this.config.usePercent()) {
                targetGridPowerVar = (int) (this.config.reactivePowerLimitPercent() / 100f * this.totalSystemPowerVar);
            } else {
                targetGridPowerVar = this.config.reactivePowerLimit();
            }
        }

        if (!hasValidSetpoint) {
            return;
        }

        // Apply closed-loop control
        this.applyClosedLoopControl(targetGridPowerVar, workingInverters);
    }

    /**
     * Closed-loop control to match grid reactive power to setpoint.
     * Uses Model-Free Robust Control (Filter + Adaptive Gain + Measured Base).
     */
    private void applyClosedLoopControl(int targetGridPowerVar, List<ManagedSymmetricPvInverter> workingInverters) 
            throws OpenemsNamedException {
        
        // --- 1. Get Feedback (Grid Meter) ---
        OpenemsComponent meterComp = null;
        try {
            meterComp = this.componentManager.getComponent(this.config.meter_id());
        } catch (OpenemsNamedException e) {
            // Fallback strategy: find meter
            for (ManagedSymmetricPvInverter inv : this.allInverters) {
                if (inv.id().toLowerCase().startsWith("smartlogger")) {
                     if (meterComp == null || inv.id().contains("0") || inv.alias().toLowerCase().contains("grid") || inv.alias().toLowerCase().contains("pcc")) {
                        meterComp = inv;
                    }
                }
            }
        }
        if (meterComp == null) return;

        int actualGridPowerRaw = 0;
        if (meterComp instanceof ElectricityMeter) {
            actualGridPowerRaw = ((ElectricityMeter) meterComp).getReactivePower().orElse(0);
        }

        // --- 2. Input Filtering ---
        double alphaIn = this.config.filterAlpha();
        if (this.qGridFilt == 0) this.qGridFilt = actualGridPowerRaw;
        this.qGridFilt = (alphaIn * actualGridPowerRaw) + ((1 - alphaIn) * this.qGridFilt);

        // --- 3. Error Calculation ---
        int errorVar = targetGridPowerVar - (int)this.qGridFilt;

        // Thresholds
        int totalCapacityVar = this.totalSystemPowerVar;
        if (totalCapacityVar == 0) totalCapacityVar = 50000; // Default
        
        int deadbandVar = (int)(totalCapacityVar * this.config.deadbandPercent() / 100.0);
        if (deadbandVar < 1000) deadbandVar = 1000;

        // Apply Deadband
        if (Math.abs(errorVar) < deadbandVar) {
            errorVar = 0;
            this.oscCounter = Math.max(0, this.oscCounter - 1);
        }

        // --- 4. Adaptive Gain ---
        boolean isOscillating = (Math.signum(errorVar) != Math.signum(this.lastError)) 
                             && (Math.abs(errorVar) > deadbandVar);
        
        double kpNom = this.config.gainKp();
        double kpMin = 0.05;

        if (isOscillating) {
            this.oscCounter++;
            if (this.oscCounter >= 3) {
                 this.currentKp = Math.max(kpMin, this.currentKp * 0.75);
                 this.oscCounter = 0;
                 this.logInfo(this.log, "Oscillation detected. Reduced Kp to: " + String.format("%.3f", this.currentKp));
            }
        } else {
            this.currentKp = Math.min(kpNom, this.currentKp + 0.001);
            if(errorVar == 0) this.oscCounter = 0;
        }
        this.lastError = errorVar;

        // --- 5. Calculate Adjustment ---
        int adjustmentVar = (int)(errorVar * this.currentKp);

        // --- 6. Ramp Limit ---
        int maxRampVar = (int)(totalCapacityVar * this.config.maxRampRatePercent() / 100.0);
        adjustmentVar = Math.min(Math.max(adjustmentVar, -maxRampVar), maxRampVar);
        
        // --- 7. Measured Base Reset ---
        // Sum actually measured Q
        int totalMeasuredQ = workingInverters.stream()
                .mapToInt(inv -> inv.getReactivePower().orElse(0))
                .sum();
        
        int rawTargetSystemQ = totalMeasuredQ + adjustmentVar;

        // --- 8. Output Smoothing ---
        double alphaOut = this.config.outputSmoothing();
        if (this.qCmdFilt == 0) this.qCmdFilt = rawTargetSystemQ;
        this.qCmdFilt = (alphaOut * rawTargetSystemQ) + ((1 - alphaOut) * this.qCmdFilt);
        
        int finalTargetSystemQ = (int)this.qCmdFilt;
        
        // Clamp total (Q can be negative)
        finalTargetSystemQ = Math.max(-totalCapacityVar, Math.min(finalTargetSystemQ, totalCapacityVar));

        // --- 9. Distribute ---
        int totalWorkingQCapacity = workingInverters.stream()
                .mapToInt(inv -> {
                    int maxQ = inv.getMaxReactivePower().orElse(0);
                    if (maxQ == 0) maxQ = (int)(inv.getMaxApparentPower().orElse(0) * 0.5);
                    return maxQ;
                })
                .sum();
        
        if (totalWorkingQCapacity <= 0) return;

        for (ManagedSymmetricPvInverter inv : workingInverters) {
            int maxQ = inv.getMaxReactivePower().orElse(0);
            if (maxQ == 0) maxQ = (int)(inv.getMaxApparentPower().orElse(0) * 0.5);
            if (maxQ <= 0) continue;

            double ratio = (double) maxQ / totalWorkingQCapacity;
            int invTarget = (int)(finalTargetSystemQ * ratio);
            
            // Clamp individual
            invTarget = Math.max(-maxQ, Math.min(invTarget, maxQ));

            try {
                inv.setReactivePowerLimit(invTarget);
                this.previousLimits.put(inv.id(), invTarget);
            } catch (Exception e) {
                this.logError(this.log, "Error setting Q limit: " + e.getMessage());
            }
        }

        this.logDebug(this.log, String.format(
                "RobustCtlQ: Err=%dvar, Adj=%dvar, Kp=%.3f, Meas=%dvar, Cmd=%dvar",
                errorVar, adjustmentVar, this.currentKp, totalMeasuredQ, finalTargetSystemQ));
    }
    
    private int readChannel(OpenemsComponent comp, String channelId) {
        return comp.channel(channelId).value().asOptional().map(v -> {
            if (v instanceof Integer) return (Integer) v;
            return 0;
        }).orElse(0);
    }
}
