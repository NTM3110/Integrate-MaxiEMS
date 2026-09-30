package io.openems.edge.controller.api.modbus.evn;

/**
 * EVN Write Register definitions for receiving control commands via Modbus.
 * 
 * <p>
 * Based on EVN specification:
 * - T05 = Read Input Registers (FC04)
 * - T06 = Read/Write Holding Registers (FC03/FC06/FC16)
 * 
 * <p>
 * All setpoint values are Float32 (IEEE-754, 2 registers each).
 * Enable registers are single 16-bit integers.
 */
public class EvnWriteRegisters {

    // ==================== P-out CONTROL REGISTERS ====================

    /**
     * Cho phép điều khiển P-out (Enable P control)
     * Address 11 (holding register)
     * Value: 0 = Disabled, 1 = Enabled
     */
    public static final int P_OUT_ENABLE_ADDRESS = 11;

    /**
     * SetPoint P-out theo % (P setpoint in percentage)
     * Addresses 13-14 (Float32)
     * Value: 0-100 (%)
     */
    public static final int P_OUT_SETPOINT_PERCENT_ADDRESS = 13;

    /**
     * SetPoint P-out theo kW (P setpoint in kW)
     * Addresses 15-16 (Float32)
     * Value: Power limit in kW
     */
    public static final int P_OUT_SETPOINT_KW_ADDRESS = 15;

    // ==================== Q-out CONTROL REGISTERS ====================

    /**
     * Cho phép điều khiển Q-out (Enable Q control)
     * Address 12 (holding register)
     * Value: 0 = Disabled, 1 = Enabled
     */
    public static final int Q_OUT_ENABLE_ADDRESS = 12;

    /**
     * SetPoint Q-out theo % (Q setpoint in percentage)
     * Addresses 17-18 (Float32)
     * Value: -100 to +100 (%)
     */
    public static final int Q_OUT_SETPOINT_PERCENT_ADDRESS = 17;

    /**
     * SetPoint Q-out theo kvar (Q setpoint in kvar)
     * Addresses 19-20 (Float32)
     * Value: Reactive power limit in kvar
     */
    public static final int Q_OUT_SETPOINT_KVAR_ADDRESS = 19;

    // ==================== Helper Methods ====================

    /**
     * Check if address is a control register.
     */
    public static boolean isControlRegister(int address) {
        return address == P_OUT_ENABLE_ADDRESS
                || address == Q_OUT_ENABLE_ADDRESS
                || isFloatControlRegister(address);
    }

    /**
     * Check if address is part of a float control register (2 registers).
     */
    public static boolean isFloatControlRegister(int address) {
        return (address >= P_OUT_SETPOINT_PERCENT_ADDRESS && address <= P_OUT_SETPOINT_PERCENT_ADDRESS + 1)
                || (address >= P_OUT_SETPOINT_KW_ADDRESS && address <= P_OUT_SETPOINT_KW_ADDRESS + 1)
                || (address >= Q_OUT_SETPOINT_PERCENT_ADDRESS && address <= Q_OUT_SETPOINT_PERCENT_ADDRESS + 1)
                || (address >= Q_OUT_SETPOINT_KVAR_ADDRESS && address <= Q_OUT_SETPOINT_KVAR_ADDRESS + 1);
    }

    /**
     * Check if address is an enable register (single 16-bit).
     */
    public static boolean isEnableRegister(int address) {
        return address == P_OUT_ENABLE_ADDRESS || address == Q_OUT_ENABLE_ADDRESS;
    }
}
