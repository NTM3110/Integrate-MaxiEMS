package io.openems.edge.bridge.iec6205621;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Generic IEC 62056-21 line/frame exchange over an exclusive transport. */
public final class Iec6205621Session {

	@FunctionalInterface
	public interface Observer {
		void wire(boolean tx, byte[] data, boolean partial) throws IOException;
	}

	public static final Observer QUIET = (tx, data, partial) -> {
	};

	private static final int MAX_FRAME_BYTES = 1024;
	private final Iec6205621Transport transport;
	private final Observer observer;
	private int receivedBytes;

	public Iec6205621Session(Iec6205621Transport transport, Observer observer) {
		this.transport = Objects.requireNonNull(transport);
		this.observer = observer == null ? QUIET : observer;
	}

	public String name() {
		return this.transport.name();
	}

	public int getReceivedBytes() {
		return this.receivedBytes;
	}

	public void send(byte[] data) throws IOException {
		this.transport.send(data);
		this.observer.wire(true, data, false);
	}

	public byte[] receiveIdentification(long deadlineNanos) throws IOException {
		return this.receive(true, deadlineNanos);
	}

	public byte[] receiveFrame(long deadlineNanos) throws IOException {
		return this.receive(false, deadlineNanos);
	}

	private byte[] receive(boolean identificationLine, long deadlineNanos) throws IOException {
		var data = new ByteArrayOutputStream();
		this.receivedBytes = 0;
		boolean logged = false;
		try {
			boolean endSeen = false;
			while (data.size() < MAX_FRAME_BYTES) {
				int value = this.transport.readByte(deadlineNanos);
				if (value < 0 || value > 255) {
					throw new IOException("Invalid serial byte / EOF");
				}
				data.write(value);
				this.receivedBytes = data.size();
				if (identificationLine) {
					byte[] bytes = data.toByteArray();
					if (bytes.length >= 2 && bytes[bytes.length - 2] == '\r' && value == '\n') {
						this.observer.wire(false, bytes, false);
						logged = true;
						return bytes;
					}
				} else if (data.size() == 1) {
					if (value == 6 || value == 21) {
						byte[] bytes = data.toByteArray();
						this.observer.wire(false, bytes, false);
						return bytes;
					}
					if (value != 1 && value != 2) {
						throw new IOException("Unexpected IEC 62056-21 frame start: " + value);
					}
				} else if (endSeen) {
					byte[] bytes = data.toByteArray();
					this.observer.wire(false, bytes, false);
					logged = true;
					Iec6205621FrameCodec.checkFrame(bytes);
					return bytes;
				} else {
					endSeen = value == 3;
				}
			}
			throw new IOException("IEC 62056-21 frame exceeds " + MAX_FRAME_BYTES + " bytes");
		} catch (IOException e) {
			try {
				if (!logged) {
					this.observer.wire(false, data.toByteArray(), true);
				}
			} catch (IOException logError) {
				e.addSuppressed(logError);
			}
			throw e;
		}
	}

	public static byte[] identificationRequest(String address) {
		return ("/?" + (address == null ? "" : address) + "!\r\n").getBytes(StandardCharsets.US_ASCII);
	}

	public static byte[] modeCSelection() {
		return new byte[] { 6, '0', '5', '1', '\r', '\n' };
	}

	public static void requireAck(byte[] response) throws IOException {
		if (response.length != 1 || response[0] != 6) {
			throw new IOException(response.length == 1 && response[0] == 21 ? "Meter returned NAK" : "Expected ACK");
		}
	}
}
