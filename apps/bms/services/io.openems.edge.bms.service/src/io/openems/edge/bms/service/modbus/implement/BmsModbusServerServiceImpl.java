package io.openems.edge.bms.service.modbus.implement;

import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.osgi.framework.Constants;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.openems.edge.bms.service.modbus.contracts.BmsModbusServerRuntime;
import io.openems.edge.bms.service.modbus.contracts.BmsModbusServerService;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.RuntimeState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.SerialPort;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.ServerState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;

/** ConfigAdmin-backed owner of the single BMS Modbus server and serial reservations. */
@Component(name = "io.openems.edge.bms.service.modbus.BmsModbusServerService", service = BmsModbusServerService.class)
public class BmsModbusServerServiceImpl implements BmsModbusServerService {

	public static final String SERVER_FACTORY = "Controller.Bms.ModbusTcp";
	public static final String SERVER_ID = "ctrlBmsModbusTcp0";
	public static final String SERIAL_FACTORY = "Bridge.Modbus.Serial";
	public static final String RESERVED_BY = "maxiems.modbusServerReservedBy";
	public static final String PREVIOUS_ENABLED = "maxiems.modbusServerPreviousEnabled";
	private static final List<String> PORT_IDS = List.of("modbus0", "modbus1", "modbus2");

	@Reference
	private ConfigurationAdmin configurationAdmin;
	private volatile BmsModbusServerRuntime runtime;

	@Reference(cardinality = OPTIONAL, policy = DYNAMIC, target = "(id=" + SERVER_ID + ")")
	protected void setRuntime(BmsModbusServerRuntime runtime) {
		this.runtime = runtime;
	}

	protected void unsetRuntime(BmsModbusServerRuntime runtime) {
		if (this.runtime == runtime) this.runtime = null;
	}

	@Override
	public synchronized ServerState get() throws Exception {
		var server = this.server(false);
		var properties = server == null ? null : server.getProperties();
		return this.state(properties);
	}

	@Override
	public synchronized ServerState update(boolean enabled, Transport transport, int unitId, String bindAddress, int tcpPort,
			String serialPortId) throws Exception {
		if (transport == null) throw new IllegalArgumentException("Transport is required");
		if (unitId < 1 || unitId > 247) throw new IllegalArgumentException("Unit ID must be between 1 and 247");
		if (transport == Transport.TCP) {
			if (tcpPort < 1 || tcpPort > 65535) {
				throw new IllegalArgumentException("TCP port must be between 1 and 65535");
			}
			validateBindAddress(bindAddress);
		} else {
			// TCP fields are hidden for RTU. Keep the persisted compatibility values valid
			// without making them prerequisites for saving a serial configuration.
			if (tcpPort < 1 || tcpPort > 65535) tcpPort = 1502;
			try {
				validateBindAddress(bindAddress);
			} catch (IllegalArgumentException e) {
				bindAddress = "0.0.0.0";
			}
		}
		if (transport == Transport.SERIAL_RTU && !PORT_IDS.contains(serialPortId)) {
			throw new IllegalArgumentException("Serial port must be Port 1, Port 2 or Port 3");
		}

		var server = this.server(false);
		var createdServer = server == null;
		if (server == null) server = this.configurationAdmin.createFactoryConfiguration(SERVER_FACTORY, "?");
		var oldServer = copy(server.getProperties());
		var bridges = this.managedBridges();
		var snapshots = new ArrayList<Snapshot>();
		for (var bridge : bridges) snapshots.add(new Snapshot(bridge, copy(bridge.getProperties())));
		try {
			var oldTransport = enumValue(oldServer, "transport", Transport.TCP);
			var oldPort = string(oldServer, "serialPortId");
			var mustReleaseOld = bool(oldServer, "enabled", false) && oldTransport == Transport.SERIAL_RTU
					&& (!enabled || transport != Transport.SERIAL_RTU || !oldPort.equals(serialPortId));
			if (mustReleaseOld) {
				var stopped = copy(oldServer);
				stopped.put("enabled", false);
				server.update(stopped);
				this.awaitStopped();
			}
			for (var bridge : bridges) {
				var properties = bridge.getProperties();
				if (SERVER_ID.equals(string(properties, RESERVED_BY))
						&& (transport != Transport.SERIAL_RTU || !string(properties, "id").equals(serialPortId) || !enabled)) {
					this.release(bridge, properties);
				}
			}
			if (enabled && transport == Transport.SERIAL_RTU) {
				var selected = exactBridge(bridges, serialPortId);
				var selectedProperties = selected.getProperties();
				var owners = this.ownerIds(serialPortId);
				if (!owners.isEmpty()) throw new IllegalStateException(displayName(serialPortId) + " is used by " + String.join(", ", owners));
				var reservedBy = string(selectedProperties, RESERVED_BY);
				if (!reservedBy.isBlank() && !SERVER_ID.equals(reservedBy)) {
					throw new IllegalStateException(displayName(serialPortId) + " is reserved by " + reservedBy);
				}
				this.assertPhysicalPathAvailable(selected);
				this.reserve(selected, selectedProperties);
			}

			var properties = copy(server.getProperties());
			properties.put("id", SERVER_ID);
			properties.put("alias", "BMS Modbus Server");
			properties.put("enabled", enabled);
			properties.put("transport", transport.name());
			properties.put("unitId", unitId);
			properties.put("bindAddress", bindAddress);
			properties.put("port", tcpPort); // compatibility property; do not rename
			properties.put("serialPortId", transport == Transport.SERIAL_RTU ? serialPortId : "");
			if (transport == Transport.SERIAL_RTU) copySerialSettings(exactBridge(bridges, serialPortId).getProperties(), properties);
			server.update(properties);
			return this.state(properties);
		} catch (Exception e) {
			try {
				for (var snapshot : snapshots) snapshot.configuration().update(snapshot.properties());
				if (createdServer) server.delete(); else server.update(oldServer);
			} catch (Exception rollback) {
				e.addSuppressed(rollback);
			}
			throw e;
		}
	}

	private void awaitStopped() throws InterruptedException {
		var runtime = this.runtime;
		if (runtime == null) return;
		var deadline = System.nanoTime() + 3_000_000_000L;
		while (runtime.runtimeState() != RuntimeState.DISABLED && System.nanoTime() < deadline) Thread.sleep(25L);
		if (runtime.runtimeState() != RuntimeState.DISABLED) {
			throw new IllegalStateException("Timed out stopping the previous Serial RTU listener");
		}
	}

	private ServerState state(Dictionary<String, Object> properties) throws Exception {
		var enabled = properties != null && bool(properties, "enabled", false);
		var transport = enumValue(properties, "transport", Transport.TCP);
		var selected = properties == null ? "" : string(properties, "serialPortId");
		var ports = new ArrayList<SerialPort>();
		for (var bridge : this.managedBridges()) {
			var p = bridge.getProperties();
			var id = string(p, "id");
			var owners = this.ownerIds(id);
			var reservedBy = string(p, RESERVED_BY);
			var selectedHere = enabled && transport == Transport.SERIAL_RTU && selected.equals(id);
			var available = owners.isEmpty() && (reservedBy.isBlank() || SERVER_ID.equals(reservedBy));
			var owner = !owners.isEmpty() ? "BMS_STRING" : !reservedBy.isBlank() ? "MODBUS_SERVER" : "NONE";
			ports.add(new SerialPort(id, displayName(id), string(p, "portName"), serialFormat(p), available,
					selectedHere, owner, List.copyOf(owners)));
		}
		var runtime = this.runtime;
		var runtimeState = runtime == null ? enabled ? RuntimeState.STARTING : RuntimeState.DISABLED : runtime.runtimeState();
		var runtimeError = runtime == null ? null : runtime.runtimeError();
		return new ServerState(enabled, transport, integer(properties, "unitId", 1), string(properties, "bindAddress").isBlank() ? "0.0.0.0" : string(properties, "bindAddress"), integer(properties, "port", 1502),
				selected, runtimeState, runtimeError, List.copyOf(ports));
	}

	private static void validateBindAddress(String value) {
		try {
			io.openems.edge.bms.service.modbus.model.IpLiteral.parse(value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Bind address must be an IPv4 or IPv6 literal", e);
		}
	}

	private Configuration server(boolean create) throws Exception {
		var configurations = this.configurationAdmin.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + SERVER_FACTORY + ")");
		Configuration result = null;
		if (configurations != null) for (var configuration : configurations) {
			var p = configuration.getProperties();
			if (p != null && SERVER_ID.equals(string(p, "id"))) {
				if (result != null) throw new IllegalStateException("Duplicate managed BMS Modbus server configuration");
				result = configuration;
			} else if (p != null && bool(p, "enabled", false)) {
				throw new IllegalStateException("Another BMS Modbus server configuration is enabled");
			}
		}
		if (result == null && create) result = this.configurationAdmin.createFactoryConfiguration(SERVER_FACTORY, "?");
		return result;
	}

	private List<Configuration> managedBridges() throws Exception {
		var all = this.configurationAdmin.listConfigurations("(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + SERIAL_FACTORY + ")");
		var result = new ArrayList<Configuration>();
		if (all != null) for (var configuration : all) {
			var id = string(configuration.getProperties(), "id");
			if (PORT_IDS.contains(id)) result.add(configuration);
		}
		result.sort(Comparator.comparing(c -> string(c.getProperties(), "id")));
		for (var id : PORT_IDS) exactBridge(result, id);
		return result;
	}

	private Set<String> ownerIds(String bridgeId) throws Exception {
		var owners = new LinkedHashSet<String>();
		var configurations = this.configurationAdmin.listConfigurations("(modbusId=*)");
		if (configurations != null) for (var configuration : configurations) {
			var p = configuration.getProperties();
			if (p == null || !bridgeId.equals(string(p, "modbusId"))) continue;
			var id = string(p, "id");
			if (!id.isBlank()) owners.add(ownerLabel(id));
		}
		return owners;
	}

	private void reserve(Configuration bridge, Dictionary<String, Object> source) throws Exception {
		if (SERVER_ID.equals(string(source, RESERVED_BY))) return;
		var p = copy(source);
		p.put(RESERVED_BY, SERVER_ID);
		p.put(PREVIOUS_ENABLED, bool(source, "enabled", false));
		p.put("enabled", false);
		bridge.update(p);
	}

	private void release(Configuration bridge, Dictionary<String, Object> source) throws Exception {
		var p = copy(source);
		p.put("enabled", bool(source, PREVIOUS_ENABLED, false));
		p.remove(RESERVED_BY);
		p.remove(PREVIOUS_ENABLED);
		bridge.update(p);
	}

	private void assertPhysicalPathAvailable(Configuration selected) throws Exception {
		var selectedPath = string(selected.getProperties(), "portName");
		var selectedId = string(selected.getProperties(), "id");
		if (selectedPath.isBlank()) throw new IllegalStateException("Selected serial port has no device path");
		var configurations = this.configurationAdmin.listConfigurations("(portName=" + escape(selectedPath) + ")");
		if (configurations == null) return;
		for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (configuration == selected || SERVER_FACTORY.equals(configuration.getFactoryPid())
					|| (SERIAL_FACTORY.equals(configuration.getFactoryPid())
							&& selectedId.equals(string(properties, "id")))) continue;
			if (properties != null && (bool(properties, "enabled", false) || !string(properties, RESERVED_BY).isBlank())) {
				throw new IllegalStateException("Physical serial path is already owned by " + string(properties, "id"));
			}
		}
	}

	private static String escape(String value) {
		return value.replace("\\", "\\5c").replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29")
				.replace("\u0000", "\\00");
	}

	private static void copySerialSettings(Dictionary<String, Object> source, Dictionary<String, Object> target) {
		target.put("portName", string(source, "portName"));
		target.put("baudRate", integer(source, "baudRate", 9600));
		target.put("databits", integer(source, "databits", 8));
		target.put("parity", string(source, "parity").isBlank() ? "NONE" : string(source, "parity"));
		target.put("stopbits", string(source, "stopbits").isBlank() ? "ONE" : string(source, "stopbits"));
	}

	private static Configuration exactBridge(List<Configuration> bridges, String id) {
		var matches = bridges.stream().filter(c -> id.equals(string(c.getProperties(), "id"))).toList();
		if (matches.size() != 1) throw new IllegalStateException("Expected exactly one managed serial bridge " + id);
		return matches.get(0);
	}

	private static String displayName(String id) { return "Port " + (PORT_IDS.indexOf(id) + 1); }
	private static String ownerLabel(String id) {
		var marker = id.indexOf("-str");
		if (marker >= 0) return id.substring(marker + 1).replace("bmsHuasuTc-", "");
		return id;
	}
	private static String serialFormat(Dictionary<String, Object> p) {
		return integer(p, "baudRate", 9600) + " " + integer(p, "databits", 8) + parityShort(string(p, "parity")) + stopShort(string(p, "stopbits"));
	}
	private static String parityShort(String value) { return switch (value) { case "EVEN" -> "E"; case "ODD" -> "O"; default -> "N"; }; }
	private static String stopShort(String value) { return switch (value) { case "TWO" -> "2"; case "ONE_POINT_FIVE" -> "1.5"; default -> "1"; }; }
	private static <T extends Enum<T>> T enumValue(Dictionary<String, Object> p, String key, T fallback) {
		if (p == null) return fallback;
		try { return Enum.valueOf(fallback.getDeclaringClass(), string(p, key)); } catch (Exception e) { return fallback; }
	}
	private static String string(Dictionary<String, Object> p, String key) { var v = p == null ? null : p.get(key); return v == null ? "" : v.toString(); }
	private static int integer(Dictionary<String, Object> p, String key, int fallback) { var v = p == null ? null : p.get(key); if (v == null) return fallback; return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()); }
	private static boolean bool(Dictionary<String, Object> p, String key, boolean fallback) { var v = p == null ? null : p.get(key); return v == null ? fallback : v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString()); }
	private static Dictionary<String, Object> copy(Dictionary<String, Object> source) {
		Dictionary<String, Object> target = new Hashtable<>();
		if (source != null) for (var keys = source.keys(); keys.hasMoreElements();) { var key = keys.nextElement(); if (!Constants.SERVICE_PID.equals(key) && !ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)) target.put(key, source.get(key)); }
		return target;
	}
	private record Snapshot(Configuration configuration, Dictionary<String, Object> properties) {}
}
