package io.openems.edge.network.manager.os;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface CommandRunner {
	String execute(List<String> command) throws IOException, InterruptedException;
}
