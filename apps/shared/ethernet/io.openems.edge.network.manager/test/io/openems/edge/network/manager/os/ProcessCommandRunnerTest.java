package io.openems.edge.network.manager.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.Test;

public class ProcessCommandRunnerTest {

	@Test
	public void drainsLargeProcessOutputWithoutDeadlock() throws Exception {
		var java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
		var runner = new ProcessCommandRunner(Duration.ofSeconds(10));

		var output = runner.execute(List.of(java, "-cp", System.getProperty("java.class.path"),
				LargeOutputProcess.class.getName()));

		assertEquals(2_000_000, output.length());
		assertTrue(output.chars().allMatch(value -> value == 'x'));
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	public static final class LargeOutputProcess {
		public static void main(String[] args) {
			System.out.print("x".repeat(2_000_000));
		}
	}
}
