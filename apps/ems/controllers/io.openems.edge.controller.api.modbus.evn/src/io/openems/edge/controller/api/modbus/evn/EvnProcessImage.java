package io.openems.edge.controller.api.modbus.evn;

import com.ghgande.j2mod.modbus.procimg.DigitalIn;
import com.ghgande.j2mod.modbus.procimg.DigitalOut;
import com.ghgande.j2mod.modbus.procimg.FIFO;
import com.ghgande.j2mod.modbus.procimg.File;
import com.ghgande.j2mod.modbus.procimg.IllegalAddressException;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

/**
 * Custom Process Image for EVN Modbus TCP slave.
 *
 * <p>
 * Responsibilities (only): <br>
 * - Store monitoring values written by ControllerApiModbusEvnImpl via
 * {@link #setInputFloat}. <br>
 * - Serve them to EVN via FC04 (getInputRegisterRange / getInputRegister). <br>
 * - Maintain holding registers for EVN write commands (FC06/FC16). <br>
 * - Expose P/Q command values to ControllerApiModbusEvnImpl via typed getters.
 *
 * <p>
 * This class does NOT read channels, discover components, or interact with
 * ComponentManager.
 *
 * <p>
 * Address convention: all public addresses are 1-based (matching Modbus data
 * model and PLAN register map). j2mod passes the same 1-based value as the
 * offset argument to getInputRegister*(). Index 0 of both arrays is unused.
 *
 * <p>
 * Float encoding: IEEE-754 big-endian (high word first), matching EVN spec.
 */
public class EvnProcessImage implements ProcessImage {

    private static final int MAX_ADDRESS = 500;

    /**
     * Input register storage (1-based). Index 0 is unused.
     * setInputFloat(base, v) stores high-word at [base], low-word at [base+1].
     */
    private final int[] inputRegisters = new int[MAX_ADDRESS + 2];

    /**
     * Holding registers (1-based). j2mod calls getRegister(ref) then setValue()
     * directly on the returned object — so we must return the SAME object each
     * time to observe FC06/FC16 writes.
     */
    private final SimpleRegister[] holdingRegisters;

    public EvnProcessImage() {
        this.holdingRegisters = new SimpleRegister[MAX_ADDRESS + 1];
        for (int i = 0; i <= MAX_ADDRESS; i++) {
            this.holdingRegisters[i] = new SimpleRegister(0);
        }
    }

    // ==================== Write API (called by ControllerApiModbusEvnImpl) ====================

    /**
     * Stores a Float32 value into the input register space.
     *
     * @param baseAddress 1-based start address (stores high-word at baseAddress,
     *                    low-word at baseAddress + 1)
     * @param value       the float value to encode as IEEE-754 big-endian
     */
    public synchronized void setInputFloat(int baseAddress, float value) {
        if (baseAddress <= 0 || baseAddress + 1 > MAX_ADDRESS) {
            return;
        }
        int bits = Float.floatToIntBits(value);
        this.inputRegisters[baseAddress] = (bits >> 16) & 0xFFFF;
        this.inputRegisters[baseAddress + 1] = bits & 0xFFFF;
    }

    // ==================== Read API (called by ControllerApiModbusEvnImpl) ====================

    /**
     * Reads a Float32 from the holding register space (two consecutive registers).
     *
     * @param baseAddress 1-based start address of the float pair
     */
    public float getHoldingFloat(int baseAddress) {
        if (baseAddress <= 0 || baseAddress + 1 > MAX_ADDRESS) {
            return 0f;
        }
        int high = this.holdingRegisters[baseAddress].getValue();
        int low = this.holdingRegisters[baseAddress + 1].getValue();
        return Float.intBitsToFloat((high << 16) | (low & 0xFFFF));
    }

    /**
     * Reads a single 16-bit integer from the holding register space.
     *
     * @param address 1-based register address
     */
    public int getHoldingInt(int address) {
        if (address <= 0 || address > MAX_ADDRESS) {
            return 0;
        }
        return this.holdingRegisters[address].getValue();
    }

    // ==================== Convenience getters for P/Q commands ====================

    public boolean isPOutEnabled() {
        return this.getHoldingInt(EvnWriteRegisters.P_OUT_ENABLE_ADDRESS) != 0;
    }

    public float getPOutSetpointPercent() {
        return this.getHoldingFloat(EvnWriteRegisters.P_OUT_SETPOINT_PERCENT_ADDRESS);
    }

    public float getPOutSetpointKw() {
        return this.getHoldingFloat(EvnWriteRegisters.P_OUT_SETPOINT_KW_ADDRESS);
    }

    public boolean isQOutEnabled() {
        return this.getHoldingInt(EvnWriteRegisters.Q_OUT_ENABLE_ADDRESS) != 0;
    }

    public float getQOutSetpointPercent() {
        return this.getHoldingFloat(EvnWriteRegisters.Q_OUT_SETPOINT_PERCENT_ADDRESS);
    }

    public float getQOutSetpointKvar() {
        return this.getHoldingFloat(EvnWriteRegisters.Q_OUT_SETPOINT_KVAR_ADDRESS);
    }

    // ==================== FC04 — Read Input Registers (monitoring) ====================

    @Override
    public synchronized InputRegister[] getInputRegisterRange(int offset, int count) throws IllegalAddressException {
        InputRegister[] result = new InputRegister[count];
        for (int i = 0; i < count; i++) {
            int addr = offset + i;
            result[i] = new SimpleInputRegister(
                    addr > 0 && addr < this.inputRegisters.length ? this.inputRegisters[addr] : 0);
        }
        return result;
    }

    @Override
    public synchronized InputRegister getInputRegister(int ref) throws IllegalAddressException {
        return new SimpleInputRegister(
                ref > 0 && ref < this.inputRegisters.length ? this.inputRegisters[ref] : 0);
    }

    @Override
    public int getInputRegisterCount() {
        return MAX_ADDRESS;
    }

    // ==================== FC03/FC06/FC16 — Holding Registers (control) ====================

    @Override
    public synchronized Register getRegister(int ref) throws IllegalAddressException {
        if (ref <= 0 || ref > MAX_ADDRESS) {
            throw new IllegalAddressException("Address " + ref + " out of range [1," + MAX_ADDRESS + "]");
        }
        return this.holdingRegisters[ref];
    }

    @Override
    public synchronized Register[] getRegisterRange(int offset, int count) throws IllegalAddressException {
        if (offset <= 0 || offset + count - 1 > MAX_ADDRESS) {
            throw new IllegalAddressException(
                    "Range [" + offset + "," + (offset + count - 1) + "] out of range");
        }
        Register[] result = new Register[count];
        for (int i = 0; i < count; i++) {
            result[i] = this.holdingRegisters[offset + i];
        }
        return result;
    }

    @Override
    public int getRegisterCount() {
        return MAX_ADDRESS;
    }

    // ==================== Unused — coil / file / FIFO ====================

    @Override
    public DigitalOut[] getDigitalOutRange(int offset, int count) {
        DigitalOut[] r = new DigitalOut[count];
        for (int i = 0; i < count; i++) {
            r[i] = new SimpleDigitalOut(false);
        }
        return r;
    }

    @Override
    public DigitalOut getDigitalOut(int ref) {
        return new SimpleDigitalOut(false);
    }

    @Override
    public int getDigitalOutCount() {
        return 0;
    }

    @Override
    public DigitalIn[] getDigitalInRange(int offset, int count) {
        DigitalIn[] r = new DigitalIn[count];
        for (int i = 0; i < count; i++) {
            r[i] = new SimpleDigitalIn(false);
        }
        return r;
    }

    @Override
    public DigitalIn getDigitalIn(int ref) {
        return new SimpleDigitalIn(false);
    }

    @Override
    public int getDigitalInCount() {
        return 0;
    }

    @Override
    public File getFile(int ref) {
        return null;
    }

    @Override
    public File getFileByNumber(int ref) {
        return null;
    }

    @Override
    public int getFileCount() {
        return 0;
    }

    @Override
    public FIFO getFIFO(int ref) {
        return null;
    }

    @Override
    public FIFO getFIFOByAddress(int ref) {
        return null;
    }

    @Override
    public int getFIFOCount() {
        return 0;
    }
}
