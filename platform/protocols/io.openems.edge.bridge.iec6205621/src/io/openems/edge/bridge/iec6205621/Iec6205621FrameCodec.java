package io.openems.edge.bridge.iec6205621;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** IEC 62056-21 programming-mode frame and BCC primitives. */
public final class Iec6205621FrameCodec {

	private Iec6205621FrameCodec() {
	}

	public static byte[] frame(String command, String payload) {
		byte[] body = (command + (payload == null ? "" : "\002" + payload) + "\003")
				.getBytes(StandardCharsets.US_ASCII);
		var out = new ByteArrayOutputStream();
		out.write(1);
		int check = 0;
		for (byte value : body) {
			out.write(value);
			check ^= value & 0xff;
		}
		out.write(check);
		return out.toByteArray();
	}

	public static void checkFrame(byte[] frame) throws IOException {
		if (frame.length < 3 || (frame[0] != 1 && frame[0] != 2) || frame[frame.length - 2] != 3) {
			throw new IOException("Invalid IEC 62056-21 frame boundary");
		}
		int check = 0;
		for (int i = 1; i < frame.length - 1; i++) {
			if (frame[i] == 3 && i != frame.length - 2) {
				throw new IOException("Early ETX in IEC 62056-21 frame");
			}
			check ^= frame[i] & 0xff;
		}
		if (check != (frame[frame.length - 1] & 0xff)) {
			throw new IOException("IEC 62056-21 BCC mismatch");
		}
	}
}
