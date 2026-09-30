package io.openems.edge.bridge.iec6205621;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class Iec6205621SessionTest {

	@Test
	public void buildsStandardIdentificationAndModeSelection() {
		assertArrayEquals("/?005!\r\n".getBytes(StandardCharsets.US_ASCII),
				Iec6205621Session.identificationRequest("005"));
		assertArrayEquals(new byte[] { 6, '0', '5', '1', '\r', '\n' }, Iec6205621Session.modeCSelection());
	}

	@Test
	public void sendsAndReceivesValidatedFrames() throws Exception {
		var response = Iec6205621FrameCodec.frame("R1", "(0102)");
		var transport = new ScriptedTransport(response);
		var observed = new ArrayList<byte[]>();
		var session = new Iec6205621Session(transport, (tx, data, partial) -> observed.add(data));
		var request = Iec6205621FrameCodec.frame("R1", "798001(10)");
		session.send(request);
		assertArrayEquals(response, session.receiveFrame(Long.MAX_VALUE));
		assertArrayEquals(request, transport.sent.get(0));
		assertEquals(2, observed.size());
	}

	@Test
	public void rejectsInvalidBcc() throws Exception {
		var response = Iec6205621FrameCodec.frame("R1", "(0102)");
		response[response.length - 1] ^= 1;
		var session = new Iec6205621Session(new ScriptedTransport(response), Iec6205621Session.QUIET);
		try {
			session.receiveFrame(Long.MAX_VALUE);
			fail("Expected invalid BCC");
		} catch (IOException expected) {
			assertEquals("IEC 62056-21 BCC mismatch", expected.getMessage());
		}
	}

	private static final class ScriptedTransport implements Iec6205621Transport {
		private final ArrayDeque<Integer> response = new ArrayDeque<>();
		private final List<byte[]> sent = new ArrayList<>();

		private ScriptedTransport(byte[] response) {
			for (byte value : response) {
				this.response.add(value & 0xff);
			}
		}

		@Override
		public void send(byte[] bytes) {
			this.sent.add(bytes.clone());
		}

		@Override
		public int readByte(long deadlineNanos) throws IOException {
			if (this.response.isEmpty()) {
				throw new IOException("No scripted response");
			}
			return this.response.remove();
		}

		@Override
		public String name() {
			return "scripted";
		}
	}
}
