package io.openems.edge.modem.manager;

import java.nio.charset.StandardCharsets;

record CommandSpec(String shellCommand, byte[] stdin) {

	CommandSpec(String shellCommand) {
		this(shellCommand, new byte[0]);
	}

	CommandSpec(String shellCommand, String stdin) {
		this(shellCommand, stdin.getBytes(StandardCharsets.UTF_8));
	}

	String stdinAsString() {
		return new String(this.stdin, StandardCharsets.UTF_8);
	}
}
