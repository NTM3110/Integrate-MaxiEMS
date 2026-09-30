package io.openems.edge.controller.api.modbus.evn;

import java.util.ArrayList;
import java.util.List;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

/**
 * EVN Modbus TCP Controller.
 *
 * <p>
 * Each cycle: reads the grid meter and all enabled ManagedSymmetricPvInverter
 * instances, writes monitoring values into the Modbus process image, and
 * exposes P/Q setpoints received from EVN as OpenEMS channels.
 *
 * <p>
 * This controller does NOT directly control inverters.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Api.ModbusTcp.Evn", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerApiModbusEvnImpl extends AbstractOpenemsComponent
        implements ControllerApiModbusEvn, Controller, OpenemsComponent {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerApiModbusEvnImpl.class);
    private static final int UNIT_ID = 1;
    private static final int MAX_IDLE_SECONDS = 60;
    private static final int REFRESH_INTERVAL_CYCLES = 30;

    @Reference
    private ComponentManager componentManager;

    private Config config;
    private EvnProcessImage evnProcessImage;
    private ModbusSlave slave;

    private ElectricityMeter meter;
    private final List<ManagedSymmetricPvInverter> inverters = new ArrayList<>();

    private int refreshCounter = 0;
    /** Tracks previous inverter count so stale tail registers are zeroed each cycle. */
    private int lastInverterCount = 0;

    public ControllerApiModbusEvnImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ControllerApiModbusEvn.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) {
        super.activate(context, config.id(), config.alias(), config.enabled());
        this.config = config;

        if (!config.enabled()) {
            return;
        }

        this.discoverComponents();

        this.evnProcessImage = new EvnProcessImage();

        try {
            this.slave = ModbusSlaveFactory.createTCPSlave(//
                    null, //
                    config.port(), //
                    config.maxConcurrentConnections(), //
                    false, //
                    MAX_IDLE_SECONDS);
            this.slave.addProcessImage(UNIT_ID, this.evnProcessImage);
            this.slave.open();
        } catch (ModbusException e) {
            LOG.error("Failed to start EVN Modbus slave on port {}: {}", config.port(), e.getMessage());
        }
    }

    @Deactivate
    public void deactivate() {
        try {
            if (this.slave != null) {
                this.slave.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing EVN Modbus slave: {}", e.getMessage());
        } finally {
            this.slave = null;
        }
        super.deactivate();
    }

    @Override
    public void run() throws OpenemsNamedException {
        this.refreshIfNeeded();

        if (this.evnProcessImage == null) {
            return;
        }

        this.updateInputRegisters();
        this.updateControlChannels();
    }

    // ==================== Discovery ====================

    private void discoverComponents() {
        // Meter: single component identified by config
        this.meter = null;
        try {
            OpenemsComponent comp = this.componentManager.getComponent(this.config.meter_id());
            if (comp instanceof ElectricityMeter m && comp.isEnabled()) {
                this.meter = m;
            }
        } catch (OpenemsNamedException e) {
            LOG.debug("Meter '{}' not available yet", this.config.meter_id());
        }

        // Inverters: all enabled ManagedSymmetricPvInverter, sorted by ID
        this.inverters.clear();
        for (OpenemsComponent comp : this.componentManager.getAllComponents()) {
            if (!comp.isEnabled()) {
                continue;
            }
            if (comp instanceof ManagedSymmetricPvInverter inv) {
                this.inverters.add(inv);
            }
        }
        this.inverters.sort((a, b) -> a.id().compareTo(b.id()));
    }

    private void refreshIfNeeded() {
        if (++this.refreshCounter >= REFRESH_INTERVAL_CYCLES) {
            this.refreshCounter = 0;
            this.discoverComponents();
        }
    }

    // ==================== Input register update (FC04 monitoring data) ====================

    private void updateInputRegisters() {
        // 1–2: Grid Active Power (W → kW)
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.GRID_ACTIVE_POWER.getAddress(),
                this.meter != null ? this.meter.getActivePower().orElse(0) * 0.001f : 0f);

        // 3–4: Total Production Power — sum of all inverters (W → kW)
        float totalPv = 0f;
        for (ManagedSymmetricPvInverter inv : this.inverters) {
            totalPv += inv.getActivePower().orElse(0);
        }
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.TOTAL_PRODUCTION_POWER.getAddress(),
                totalPv * 0.001f);

        // 5–6: Production Energy Yesterday (kWh, scale 1.0)
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.PRODUCTION_ENERGY.getAddress(),
                this.readEnergyYesterday());

        // 7–24: Meter phase data — zero all when meter unavailable to avoid stale reads
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.GRID_REACTIVE_POWER.getAddress(),
                this.meter != null ? this.meter.getReactivePower().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.VOLTAGE_L1.getAddress(),
                this.meter != null ? this.meter.getVoltageL1().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.VOLTAGE_L2.getAddress(),
                this.meter != null ? this.meter.getVoltageL2().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.VOLTAGE_L3.getAddress(),
                this.meter != null ? this.meter.getVoltageL3().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.CURRENT_L1.getAddress(),
                this.meter != null ? this.meter.getCurrentL1().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.CURRENT_L2.getAddress(),
                this.meter != null ? this.meter.getCurrentL2().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.CURRENT_L3.getAddress(),
                this.meter != null ? this.meter.getCurrentL3().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.FREQUENCY.getAddress(),
                this.meter != null ? this.meter.getFrequency().orElse(0) * 0.001f : 0f);
        this.evnProcessImage.setInputFloat(EvnRegisterMapping.POWER_FACTOR.getAddress(),
                this.meter != null ? this.readPowerFactor(this.meter) : 0f);

        // 25+: Each PV inverter ActivePower (W → kW), 2 registers each, sort by ID
        int curCount = Math.min(this.inverters.size(), EvnRegisterMapping.MAX_INVERTERS);
        for (int i = 0; i < curCount; i++) {
            this.evnProcessImage.setInputFloat(EvnRegisterMapping.inverterAddress(i),
                    this.inverters.get(i).getActivePower().orElse(0) * 0.001f);
        }
        // Zero out tail registers left over from a previous larger inverter list
        int prevCount = Math.min(this.lastInverterCount, EvnRegisterMapping.MAX_INVERTERS);
        for (int i = curCount; i < prevCount; i++) {
            this.evnProcessImage.setInputFloat(EvnRegisterMapping.inverterAddress(i), 0f);
        }
        this.lastInverterCount = curCount;
    }

    // ==================== Control channel update (FC03 holding registers) ====================

    private void updateControlChannels() {
        this._setPOutEnabled(this.evnProcessImage.isPOutEnabled());
        this._setPOutSetpointPercent(this.evnProcessImage.getPOutSetpointPercent());
        this._setPOutSetpointWatt((int) (this.evnProcessImage.getPOutSetpointKw() * 1000f));

        this._setQOutEnabled(this.evnProcessImage.isQOutEnabled());
        this._setQOutSetpointPercent(this.evnProcessImage.getQOutSetpointPercent());
        this._setQOutSetpointVar((int) (this.evnProcessImage.getQOutSetpointKvar() * 1000f));
    }

    // ==================== Helpers ====================

    /** Reads EnergyYesterday (kWh) from the _data component. Returns 0 on any error. */
    private float readEnergyYesterday() {
        try {
            OpenemsComponent data = this.componentManager.getComponent("_data");
            Channel<?> ch = data.channel("EnergyYesterday");
            if (!ch.value().isDefined()) {
                return 0f;
            }
            return ((Number) ch.value().get()).floatValue();
        } catch (Exception e) {
            return 0f;
        }
    }

    /**
     * Reads PowerFactor from the meter. Handles per-mille encoding: if |pf| > 1.5
     * the channel returns a value in range 0–1000; divide by 1000 to get 0.0–1.0.
     */
    private float readPowerFactor(ElectricityMeter meter) {
        try {
            Channel<?> ch = meter.channel("PowerFactor");
            if (!ch.value().isDefined()) {
                return 0f;
            }
            float pf = ((Number) ch.value().get()).floatValue();
            if (Math.abs(pf) > 1.5f) {
                pf *= 0.001f;
            }
            return pf;
        } catch (Exception e) {
            return 0f;
        }
    }
}
