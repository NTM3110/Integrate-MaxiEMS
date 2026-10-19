package io.openems.edge.bms.service.maintenance.implement;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Fixed-register physical TA/TC address change, independent of ConfigAdmin. */
final class ModuleAddressChange {
	interface Transport {
		int readModule(int unitId) throws Exception;
		void writeAddress(int unitId, int register, int newId) throws Exception;
	}

	record Result(String verification, String phase) {
		boolean verified() {
			return "VERIFIED_NEW_ID".equals(this.verification);
		}
	}

	private ModuleAddressChange() {
	}

	static int register(String moduleType) {
		if (moduleType == null) throw new IllegalArgumentException("moduleType must be TA or TC");
		return switch (moduleType) {
		case "TA" -> 0x000e;
		case "TC" -> 0x0009;
		default -> throw new IllegalArgumentException("moduleType must be TA or TC");
		};
	}

	static void validate(String moduleType, int oldId, int newId) {
		register(moduleType);
		if (oldId < 1 || oldId > 247 || newId < 1 || newId > 247) {
			throw new IllegalArgumentException("Modbus unit IDs must be between 1 and 247");
		}
		if (oldId == newId) throw new IllegalArgumentException("oldId and newId must differ");
	}

	static Result execute(String moduleType, int oldId, int newId, Transport transport,
			BooleanSupplier stopped, Consumer<String> phase, Consumer<String> log) {
		int register = register(moduleType);
		if (stopped.getAsBoolean()) return new Result("STOPPED", "preflight");
		phase.accept("check-target");
		if (responds(transport, newId, log)) {
			log.accept("Target ID " + newId + " already responds; no address write performed");
			return new Result("TARGET_ID_OCCUPIED", "check-target");
		}
		if (stopped.getAsBoolean()) return new Result("STOPPED", "check-target");
		phase.accept("check-source");
		if (!responds(transport, oldId, log)) {
			log.accept("Source ID " + oldId + " does not respond; no address write performed");
			return new Result("OLD_ID_MISSING", "check-source");
		}
		if (stopped.getAsBoolean()) return new Result("STOPPED", "check-source");
		phase.accept("write");
		try {
			transport.writeAddress(oldId, register, newId);
			log.accept("FC06 address write acknowledged at register 0x" + String.format("%04X", register));
		} catch (Exception e) {
			log.accept("FC06 acknowledgement unavailable: " + e.getMessage() + "; checking both IDs before deciding outcome");
		}
		if (stopped.getAsBoolean()) return new Result("STOPPED", "write");
		phase.accept("verify-target");
		// The module may restart after accepting FC06. Probe again without issuing a
		// second write: a missing ACK may mean the first write already succeeded.
		for (int attempt = 0; attempt < 10 && !stopped.getAsBoolean(); attempt++) {
			if (responds(transport, newId, log)) {
				log.accept("New ID " + newId + " responds after write");
				return new Result("VERIFIED_NEW_ID", "verify-target");
			}
		}
		if (stopped.getAsBoolean()) return new Result("STOPPED", "verify-target");
		phase.accept("verify-source");
		if (responds(transport, oldId, log)) {
			log.accept("Old ID " + oldId + " still responds; new ID unverified");
			return new Result("OLD_ID_RESPONDS", "verify-source");
		}
		log.accept("Neither ID responds; physical module address is unknown");
		return new Result("UNKNOWN", "verify-source");
	}

	private static boolean responds(Transport transport, int id, Consumer<String> log) {
		try {
			int value = transport.readModule(id);
			log.accept("FC03 unit " + id + " register 0x0000 replied: " + value);
			return true;
		} catch (Exception e) {
			log.accept("FC03 unit " + id + " register 0x0000: " + e.getMessage());
			return false;
		}
	}
}
