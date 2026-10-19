package io.openems.edge.bms.service.maintenance.implement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ModuleAddressChangeTest {
	private static final class Bus implements ModuleAddressChange.Transport {
		final Set<Integer> responders = new HashSet<>();
		int writes;
		int register = -1;
		boolean loseAck;
		boolean ignoreWrite;
		boolean loseModule;

		@Override public int readModule(int id) throws Exception {
			if (!this.responders.contains(id)) throw new IOException("timeout");
			return 123;
		}

		@Override public void writeAddress(int id, int register, int newId) throws Exception {
			this.writes++;
			this.register = register;
			if (!this.ignoreWrite) {
				this.responders.remove(id);
				if (!this.loseModule) this.responders.add(newId);
			}
			if (this.loseAck) throw new IOException("ACK timeout");
		}
	}

	private static ModuleAddressChange.Result execute(String type, Bus bus) {
		return ModuleAddressChange.execute(type, 1, 2, bus, () -> false, ignored -> {}, ignored -> {});
	}

	@Test public void rejectsOccupiedTargetWithoutWrite() {
		var bus = new Bus();
		bus.responders.addAll(Set.of(1, 2));
		assertEquals("TARGET_ID_OCCUPIED", execute("TA", bus).verification());
		assertEquals(0, bus.writes);
	}

	@Test public void rejectsMissingSourceWithoutWrite() {
		var bus = new Bus();
		assertEquals("OLD_ID_MISSING", execute("TA", bus).verification());
		assertEquals(0, bus.writes);
	}

	@Test public void lostAckWithNewResponderIsVerifiedWithoutDuplicateWrite() {
		var bus = new Bus();
		bus.responders.add(1);
		bus.loseAck = true;
		var phases = new ArrayList<String>();
		var result = ModuleAddressChange.execute("TA", 1, 2, bus, () -> false, phases::add, ignored -> {});
		assertTrue(result.verified());
		assertEquals(0x000e, bus.register);
		assertEquals(1, bus.writes);
		assertEquals(List.of("check-target", "check-source", "write", "verify-target"), phases);
	}

	@Test public void tcUsesDifferentFixedRegister() {
		var bus = new Bus();
		bus.responders.add(1);
		assertTrue(execute("TC", bus).verified());
		assertEquals(0x0009, bus.register);
		assertThrows(IllegalArgumentException.class, () -> ModuleAddressChange.register("OTHER"));
	}

	@Test public void oldIdStillRespondsWhenWriteWasIgnored() {
		var bus = new Bus();
		bus.responders.add(1);
		bus.ignoreWrite = true;
		bus.loseAck = true;
		var result = execute("TA", bus);
		assertFalse(result.verified());
		assertEquals("OLD_ID_RESPONDS", result.verification());
		assertEquals(1, bus.writes);
	}

	@Test public void neitherIdRespondsIsUncertain() {
		var bus = new Bus();
		bus.responders.add(1);
		bus.loseModule = true;
		assertEquals("UNKNOWN", execute("TA", bus).verification());
	}

	@Test public void stopBeforeWriteDoesNotMutateBus() {
		var bus = new Bus();
		bus.responders.add(1);
		assertEquals("STOPPED", ModuleAddressChange.execute("TA", 1, 2, bus,
				() -> true, ignored -> {}, ignored -> {}).verification());
		assertEquals(0, bus.writes);
	}

	@Test public void validatesUnitIdsBeforeOpeningSerialPort() {
		for (int invalid : new int[] {0, 248, -1}) {
			assertThrows(IllegalArgumentException.class, () -> ModuleAddressChange.validate("TA", invalid, 2));
			assertThrows(IllegalArgumentException.class, () -> ModuleAddressChange.validate("TC", 1, invalid));
		}
		assertThrows(IllegalArgumentException.class, () -> ModuleAddressChange.validate("TA", 1, 1));
		assertThrows(IllegalArgumentException.class, () -> ModuleAddressChange.validate(null, 1, 2));
		ModuleAddressChange.validate("TC", 1, 247);
	}
}
