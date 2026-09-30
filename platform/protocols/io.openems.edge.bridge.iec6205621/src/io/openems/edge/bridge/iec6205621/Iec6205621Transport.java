package io.openems.edge.bridge.iec6205621;

import java.io.IOException;

/**
 * Exclusive byte transport supplied by an IEC 62056-21 bridge transaction.
 */
public interface Iec6205621Transport {

	/** Sends bytes to the serial device. */
	void send(byte[] bytes) throws IOException;

	/** Reads one byte before the monotonic deadline. */
	int readByte(long deadlineNanos) throws IOException;

	/** Returns a diagnostic name for the transport. */
	String name();
}
