package io.openems.edge.bridge.iec6205621;

import com.fazecast.jSerialComm.SerialPort;

/** Configurable serial stop bits. */
public enum SerialStopBits {
	ONE(SerialPort.ONE_STOP_BIT), ONE_POINT_FIVE(SerialPort.ONE_POINT_FIVE_STOP_BITS), TWO(SerialPort.TWO_STOP_BITS);

	final int value;

	SerialStopBits(int value) {
		this.value = value;
	}
}
