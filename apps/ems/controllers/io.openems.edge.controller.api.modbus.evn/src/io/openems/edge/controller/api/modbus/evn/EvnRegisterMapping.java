package io.openems.edge.controller.api.modbus.evn;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for EVN Modbus register addresses, descriptions, and
 * scale factors. ControllerApiModbusEvnImpl uses these constants to know which
 * address to write via EvnProcessImage.setInputFloat().
 *
 * <p>
 * Input register address space (FC04, read-only, 1-based): <br>
 * 1–2 Grid Active Power (kW) — meter_id()/ActivePower × 0.001 <br>
 * 3–4 Total Production Power (kW) — sum ManagedSymmetricPvInverter × 0.001 <br>
 * 5–6 Production Energy Yesterday (kWh) — _data/EnergyYesterday × 1.0 <br>
 * 7–8 Grid Reactive Power (kVar) — meter_id()/ReactivePower × 0.001 <br>
 * 9–10 Voltage L1 (V) — meter_id()/VoltageL1 × 0.001 <br>
 * 11–12 Voltage L2 (V) — meter_id()/VoltageL2 × 0.001 <br>
 * 13–14 Voltage L3 (V) — meter_id()/VoltageL3 × 0.001 <br>
 * 15–16 Current L1 (A) — meter_id()/CurrentL1 × 0.001 <br>
 * 17–18 Current L2 (A) — meter_id()/CurrentL2 × 0.001 <br>
 * 19–20 Current L3 (A) — meter_id()/CurrentL3 × 0.001 <br>
 * 21–22 Frequency (Hz) — meter_id()/Frequency × 0.001 <br>
 * 23–24 Power Factor — meter_id()/PowerFactor, auto-scale <br>
 * 25+ PV Inverter i ActivePower (kW) — 2 registers each, sort by ID
 *
 * <p>
 * Holding register address space (FC03/FC06/FC16, 1-based): see
 * EvnWriteRegisters.
 */
public class EvnRegisterMapping {

    public static final int INVERTER_START_ADDRESS = 25;
    /** Each inverter occupies one Float32 = 2 Modbus registers. */
    public static final int REGISTERS_PER_INVERTER = 2;
    public static final int MAX_INVERTERS = 20;

    private final int address;
    private final String description;
    private final String channelAddress;
    private final float scaleFactor;

    private EvnRegisterMapping(int address, String description, String channelAddress, float scaleFactor) {
        this.address = address;
        this.description = description;
        this.channelAddress = channelAddress;
        this.scaleFactor = scaleFactor;
    }

    // ==================== Static Input Registers (1–24) ====================

    public static final EvnRegisterMapping GRID_ACTIVE_POWER = new EvnRegisterMapping(1,
            "P-out: Grid Active Power (kW)", "meter_id()/ActivePower", 0.001f);

    public static final EvnRegisterMapping TOTAL_PRODUCTION_POWER = new EvnRegisterMapping(3,
            "Pinv-out: Total Production Power (kW)", "sum(ManagedSymmetricPvInverter)/ActivePower", 0.001f);

    /** kWh channel — scale 1.0 (already in kWh). */
    public static final EvnRegisterMapping PRODUCTION_ENERGY = new EvnRegisterMapping(5,
            "Ainv: Total Production Energy Yesterday (kWh)", "_data/EnergyYesterday", 1.0f);

    public static final EvnRegisterMapping GRID_REACTIVE_POWER = new EvnRegisterMapping(7,
            "Q-out: Grid Reactive Power (kVar)", "meter_id()/ReactivePower", 0.001f);

    public static final EvnRegisterMapping VOLTAGE_L1 = new EvnRegisterMapping(9,
            "Ua: Phase Voltage L1 (V)", "meter_id()/VoltageL1", 0.001f);

    public static final EvnRegisterMapping VOLTAGE_L2 = new EvnRegisterMapping(11,
            "Ub: Phase Voltage L2 (V)", "meter_id()/VoltageL2", 0.001f);

    public static final EvnRegisterMapping VOLTAGE_L3 = new EvnRegisterMapping(13,
            "Uc: Phase Voltage L3 (V)", "meter_id()/VoltageL3", 0.001f);

    public static final EvnRegisterMapping CURRENT_L1 = new EvnRegisterMapping(15,
            "Ia: Phase Current L1 (A)", "meter_id()/CurrentL1", 0.001f);

    public static final EvnRegisterMapping CURRENT_L2 = new EvnRegisterMapping(17,
            "Ib: Phase Current L2 (A)", "meter_id()/CurrentL2", 0.001f);

    public static final EvnRegisterMapping CURRENT_L3 = new EvnRegisterMapping(19,
            "Ic: Phase Current L3 (A)", "meter_id()/CurrentL3", 0.001f);

    public static final EvnRegisterMapping FREQUENCY = new EvnRegisterMapping(21,
            "f: Grid Frequency (Hz)", "meter_id()/Frequency", 0.001f);

    /** Power factor: per-mille encoding (0–1000) → × 0.001 → 0.0–1.0. Auto-scale applied in Impl. */
    public static final EvnRegisterMapping POWER_FACTOR = new EvnRegisterMapping(23,
            "Cos Phi: Power Factor", "meter_id()/PowerFactor", 0.001f);

    // ==================== Dynamic Inverter Block (25+) ====================

    /**
     * Returns the 1-based start address for inverter {@code index} (0-based).
     * Formula: 25 + index * 2.
     */
    public static int inverterAddress(int index) {
        return INVERTER_START_ADDRESS + index * REGISTERS_PER_INVERTER;
    }

    /**
     * Creates a mapping entry for inverter {@code index} (0-based).
     * Address: 25 + index * 2 (Float32 ActivePower only, no energy per inverter).
     */
    public static EvnRegisterMapping createInverterActivePower(int index) {
        return new EvnRegisterMapping(
                inverterAddress(index),
                "PV Inverter " + index + " Active Power (kW)",
                "ManagedSymmetricPvInverter[" + index + "]/ActivePower",
                0.001f);
    }

    // ==================== Collections ====================

    /** All static monitoring mappings (addresses 1–24). */
    public static List<EvnRegisterMapping> getStaticMappings() {
        List<EvnRegisterMapping> m = new ArrayList<>();
        m.add(GRID_ACTIVE_POWER);       // 1–2:  meter/ActivePower
        m.add(TOTAL_PRODUCTION_POWER);  // 3–4:  sum inverters
        m.add(PRODUCTION_ENERGY);       // 5–6:  _data/EnergyYesterday
        m.add(GRID_REACTIVE_POWER);     // 7–8:  meter/ReactivePower
        m.add(VOLTAGE_L1);              // 9–10
        m.add(VOLTAGE_L2);              // 11–12
        m.add(VOLTAGE_L3);              // 13–14
        m.add(CURRENT_L1);              // 15–16
        m.add(CURRENT_L2);              // 17–18
        m.add(CURRENT_L3);              // 19–20
        m.add(FREQUENCY);               // 21–22
        m.add(POWER_FACTOR);            // 23–24
        return m;
    }

    /** Static + dynamic inverter mappings for {@code inverterCount} inverters. */
    public static List<EvnRegisterMapping> getAllMappings(int inverterCount) {
        List<EvnRegisterMapping> mappings = getStaticMappings();
        int count = Math.min(inverterCount, MAX_INVERTERS);
        for (int i = 0; i < count; i++) {
            mappings.add(createInverterActivePower(i));
        }
        return mappings;
    }

    // ==================== Getters ====================

    public int getAddress() {
        return this.address;
    }

    public String getDescription() {
        return this.description;
    }

    public String getChannelAddress() {
        return this.channelAddress;
    }

    public float getScaleFactor() {
        return this.scaleFactor;
    }

    @Override
    public String toString() {
        return "Reg" + this.address + ": " + this.description + " -> " + this.channelAddress;
    }
}
