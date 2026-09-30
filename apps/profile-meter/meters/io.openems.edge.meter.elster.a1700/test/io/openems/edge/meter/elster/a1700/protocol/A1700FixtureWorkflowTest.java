package io.openems.edge.meter.elster.a1700.protocol;

import static io.openems.edge.meter.elster.a1700.protocol.A1700Protocol.frame;
import static io.openems.edge.meter.elster.a1700.protocol.A1700Protocol.hex;
import static io.openems.edge.meter.elster.a1700.protocol.A1700Protocol.unhex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.openems.edge.bridge.iec6205621.Iec6205621Transport;

public class A1700FixtureWorkflowTest {
	private static final byte[] KEY = unhex("0102030405060708");
	private static final byte[] CHALLENGE = unhex("1020304050607080");

	private record Step(byte[] tx, byte[] rx) {
	}

	private static final class ScriptTransport implements Iec6205621Transport {
		private final List<Step> steps;
		private int sent;
		private byte[] reply = new byte[0];
		private int offset;

		private ScriptTransport(List<Step> steps) {
			this.steps = steps;
		}

		@Override
		public void send(byte[] bytes) throws IOException {
			if (this.offset != this.reply.length || this.sent >= this.steps.size()) {
				throw new IOException("Unexpected request");
			}
			var step = this.steps.get(this.sent++);
			if (!Arrays.equals(bytes, step.tx())) {
				throw new IOException("Request mismatch: " + A1700Protocol.render(bytes));
			}
			this.reply = step.rx();
			this.offset = 0;
		}

		@Override
		public int readByte(long deadlineNanos) throws IOException {
			if (this.offset == this.reply.length) {
				throw new SocketTimeoutException("No scripted response");
			}
			return this.reply[this.offset++] & 0xff;
		}

		@Override
		public String name() {
			return "fixture";
		}
	}

	@Test
	public void allReadWorkflowsMatchCapturedRequests() throws Exception {
		for (var workflow : List.of(//
				new String[] { "current", "current" }, //
				new String[] { "instantaneous", "instantaneous" }, //
				new String[] { "historical", "historical" }, //
				new String[] { "load-profile", "profile-short" })) {
			var script = new ScriptTransport(this.plan(workflow[1]));
			var config = new A1700Client.Config();
			config.delayMs = 0;
			var capture = new A1700Client(script, config, A1700Client.QUIET).capture(workflow[0], KEY);
			assertTrue(workflow[0] + ": " + capture.error, capture.complete);
			assertNull(capture.error);
			assertEquals(script.steps.size(), script.sent);
			Map<String, Object> decoded = A1700Decoder.decode(capture);
			assertEquals("complete", decoded.get("transferStatus"));
		}
	}

	private List<Step> plan(String name) throws Exception {
		var requests = new ArrayList<Step>();
		try (var input = A1700FixtureWorkflowTest.class.getResourceAsStream("/resources/a1700/" + name + ".tsv")) {
			if (input == null) {
				throw new IOException("Missing fixture " + name);
			}
			try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
				for (String row; (row = reader.readLine()) != null;) {
					if (row.startsWith("#")) {
						continue;
					}
					var fields = row.split("\\t");
					requests.add(new Step(frame(fields[1], fields[2] + "(" + fields[3] + ")"),
							fields[4].equals("-") ? new byte[0] : unhex(fields[4])));
				}
			}
		}
		var steps = new ArrayList<Step>();
		steps.add(new Step("/?005!\r\n".getBytes(StandardCharsets.US_ASCII),
				"/GEC5090100301113@000\r\n".getBytes(StandardCharsets.US_ASCII)));
		steps.add(new Step(new byte[] { 6, '0', '5', '1', 13, 10 }, frame("P0", "(" + hex(CHALLENGE) + ")")));
		steps.add(new Step(frame("P2", "(0000000000000000)"), new byte[] { 6 }));
		steps.add(requests.remove(0));
		steps.add(requests.remove(0));
		steps.add(new Step(frame("P2", "(99BBEE3287ED64EC)"), new byte[] { 6 }));
		steps.addAll(requests);
		steps.add(new Step(frame("B0", null), new byte[0]));
		return steps;
	}
}
