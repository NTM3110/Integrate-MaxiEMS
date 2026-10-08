package io.openems.edge.bms.controllers.modbus;

import java.util.Map;
import java.util.function.Supplier;

import com.ghgande.j2mod.modbus.procimg.DigitalIn;
import com.ghgande.j2mod.modbus.procimg.DigitalOut;
import com.ghgande.j2mod.modbus.procimg.FIFO;
import com.ghgande.j2mod.modbus.procimg.File;
import com.ghgande.j2mod.modbus.procimg.IllegalAddressException;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;

import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringModbusMap;

/** Process image which deliberately implements input registers only (FC4). */
public class BmsModbusProcessImage implements ProcessImage {
	private static final int MAX_READ_COUNT = 125;
	private final Supplier<Map<Integer, BmsMonitoringSnapshot>> monitoringFrames;

	public BmsModbusProcessImage(Supplier<Map<Integer, BmsMonitoringSnapshot>> monitoringFrames) {
		this.monitoringFrames = monitoringFrames;
	}

	@Override
	public InputRegister[] getInputRegisterRange(int offset, int count) throws IllegalAddressException {
		if (count < 1 || count > MAX_READ_COUNT || offset < 0 || offset > Integer.MAX_VALUE - count) {
			throw new IllegalAddressException("Invalid input register range");
		}
		var stringIndex = BmsMonitoringModbusMap.stringIndexForOffset(offset);
		var endStringIndex = BmsMonitoringModbusMap.stringIndexForOffset(offset + count - 1);
		if (stringIndex < 1 || stringIndex != endStringIndex) {
			throw new IllegalAddressException("Input register range crosses string blocks");
		}
		var blockOffset = offset % BmsMonitoringModbusMap.STRING_STRIDE;
		var firstAddress = blockOffset + 1;
		var lastAddress = firstAddress + count - 1;
		if (!BmsMonitoringModbusMap.isMonitoringAddress(firstAddress)
				|| !BmsMonitoringModbusMap.isMonitoringAddress(lastAddress)) {
			throw new IllegalAddressException("Unsupported BMS monitoring register range");
		}

		var result = new InputRegister[count];
		try {
			var frames = this.monitoringFrames.get();
			if (!frames.containsKey(stringIndex)) {
				throw new IllegalAddressException("Monitoring provider for string " + stringIndex + " is not configured");
			}
			// Capture one immutable provider frame for the complete FC4 request.
			var frame = frames.get(stringIndex);
			var registers = BmsMonitoringModbusMap.registers(frame);
			for (var i = 0; i < count; i++) {
				var value = registers.get(firstAddress + i);
				if (value == null) throw new IllegalArgumentException("Unsupported BMS monitoring address "
						+ (firstAddress + i));
				result[i] = new SimpleInputRegister(value);
			}
		} catch (IllegalArgumentException e) {
			throw new IllegalAddressException(e.getMessage());
		}
		return result;
	}

	@Override
	public InputRegister getInputRegister(int ref) throws IllegalAddressException {
		return this.getInputRegisterRange(ref, 1)[0];
	}

	/* FC3 and all writable/bit/file functions are intentionally unsupported. */
	@Override
	public Register[] getRegisterRange(int offset, int count) throws IllegalAddressException {
		throw new IllegalAddressException("Holding registers are not supported");
	}

	@Override
	public Register getRegister(int ref) throws IllegalAddressException {
		throw new IllegalAddressException("Holding registers are not supported");
	}

	@Override
	public int getInputRegisterCount() { return 0; }
	@Override
	public DigitalOut[] getDigitalOutRange(int offset, int count) throws IllegalAddressException { throw unsupported(); }
	@Override
	public DigitalOut getDigitalOut(int ref) throws IllegalAddressException { throw unsupported(); }
	@Override
	public int getDigitalOutCount() { return 0; }
	@Override
	public DigitalIn[] getDigitalInRange(int offset, int count) throws IllegalAddressException { throw unsupported(); }
	@Override
	public DigitalIn getDigitalIn(int ref) throws IllegalAddressException { throw unsupported(); }
	@Override
	public int getDigitalInCount() { return 0; }
	@Override
	public int getRegisterCount() { return 0; }
	@Override
	public File getFile(int ref) throws IllegalAddressException { throw unsupported(); }
	@Override
	public File getFileByNumber(int ref) throws IllegalAddressException { throw unsupported(); }
	@Override
	public int getFileCount() { return 0; }
	@Override
	public FIFO getFIFO(int ref) throws IllegalAddressException { throw unsupported(); }
	@Override
	public FIFO getFIFOByAddress(int ref) throws IllegalAddressException { throw unsupported(); }
	@Override
	public int getFIFOCount() { return 0; }

	private static IllegalAddressException unsupported() {
		return new IllegalAddressException("Read-only BMS input registers only");
	}
}
