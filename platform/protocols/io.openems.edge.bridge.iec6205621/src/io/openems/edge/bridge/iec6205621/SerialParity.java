package io.openems.edge.bridge.iec6205621;

import com.fazecast.jSerialComm.SerialPort;

/** Configurable serial parity. */
public enum SerialParity {
	NONE(SerialPort.NO_PARITY), ODD(SerialPort.ODD_PARITY), EVEN(SerialPort.EVEN_PARITY),
	MARK(SerialPort.MARK_PARITY), SPACE(SerialPort.SPACE_PARITY);

	final int value;

	SerialParity(int value) {
		this.value = value;
	}
}
