package io.openems.edge.bms.controllers.modbus;

import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.util.SerialParameters;

import io.openems.edge.bms.service.modbus.contracts.BmsModbusServerRuntime;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.RuntimeState;
import io.openems.edge.bms.service.modbus.model.BmsModbusServerModels.Transport;
import io.openems.edge.bms.service.modbus.model.IpLiteral;
import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringModbusMap;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.bridge.modbus.api.Parity;
import io.openems.edge.bridge.modbus.api.Stopbit;

/** Read-only Modbus/TCP projections of one immutable monitoring snapshot per request. */
@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.Bms.ModbusTcp", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerBmsModbusTcpImpl extends AbstractOpenemsComponent
		implements OpenemsComponent, Controller, BmsModbusServerRuntime {
	private static final Logger LOG = LoggerFactory.getLogger(ControllerBmsModbusTcpImpl.class);
	private final Map<BmsMonitoringSnapshotProvider, Integer> monitoringProviders = new ConcurrentHashMap<>();
	private final BmsModbusProcessImage processImage = new BmsModbusProcessImage(this::monitoringFrames);
	private ModbusSlave slave;
	private volatile RuntimeState runtimeState = RuntimeState.DISABLED;
	private volatile String runtimeError;

	public ControllerBmsModbusTcpImpl() {
		super(OpenemsComponent.ChannelId.values(), Controller.ChannelId.values());
	}

	@Activate
	protected synchronized void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.start(config);
	}

	@Modified
	protected synchronized void modified(ComponentContext context, Config config) {
		if (!this.stop()) return;
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.start(config);
	}

	@Deactivate
	protected synchronized void deactivate() {
		this.stop();
		super.deactivate();
	}

	@Reference(service = BmsMonitoringSnapshotProvider.class, cardinality = MULTIPLE, policy = DYNAMIC)
	protected void addMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		var matcher = Pattern.compile("str(\\d+)").matcher(provider.stringKey());
		if (!matcher.matches()) return;
		try {
			var stringIndex = Integer.parseInt(matcher.group(1));
			if (stringIndex >= 1 && stringIndex <= BmsMonitoringModbusMap.MAX_STRING_INDEX) {
				this.monitoringProviders.put(provider, stringIndex);
			}
		} catch (NumberFormatException e) {
			// A string key outside the supported integer range is not a Modbus provider.
		}
	}

	protected void removeMonitoringProvider(BmsMonitoringSnapshotProvider provider) {
		this.monitoringProviders.remove(provider);
	}

	/** The listener is event-driven; controller scheduling must not poll BMS data. */
	@Override
	public void run() {
		// Snapshots are read only when an FC4 request arrives.
	}

	private void start(Config config) {
		this.runtimeError = null;
		if (!config.enabled()) {
			this.runtimeState = RuntimeState.DISABLED;
			return;
		}
		this.runtimeState = RuntimeState.STARTING;
		if (config.unitId() < 1 || config.unitId() > 247
				|| (config.transport() == Transport.TCP && (config.port() < 1 || config.port() > 65535))) {
			this.fail("BMS Modbus server has invalid TCP port or unit ID", null);
			return;
		}
		try {
			this.slave = config.transport() == Transport.SERIAL_RTU ? createSerialSlave(config)
					: ModbusSlaveFactory.createTCPSlave(IpLiteral.parse(config.bindAddress()), config.port(), 10, false, 60);
			this.slave.addProcessImage(config.unitId(), this.processImage);
			this.slave.open();
			this.runtimeState = RuntimeState.ACTIVE;
			LOG.info("BMS Modbus server active over {} with unit-ID {}", config.transport(), config.unitId());
		} catch (Exception e) {
			this.fail("Unable to start BMS Modbus server over " + config.transport() + ": " + e.getMessage(), e);
			this.slave = null;
		}
	}

	private static ModbusSlave createSerialSlave(Config config) throws Exception {
		if (config.portName().isBlank()) throw new IllegalArgumentException("Serial device path is missing");
		var params = new SerialParameters();
		params.setPortName(config.portName());
		params.setBaudRate(config.baudRate());
		params.setDatabits(config.databits());
		params.setParity(Parity.valueOf(config.parity()).getValue());
		params.setStopbits(Stopbit.valueOf(config.stopbits()).getValue());
		params.setEncoding(Modbus.SERIAL_ENCODING_RTU);
		params.setEcho(false);
		return ModbusSlaveFactory.createSerialSlave(params);
	}

	private void fail(String message, Exception cause) {
		this.runtimeState = RuntimeState.ERROR;
		this.runtimeError = message;
		if (cause == null) LOG.error(message); else LOG.error(message, cause);
	}

	private boolean stop() {
		if (this.slave == null) {
			this.runtimeState = RuntimeState.DISABLED;
			return true;
		}
		try {
			this.slave.close();
			this.slave = null;
			this.runtimeState = RuntimeState.DISABLED;
			return true;
		} catch (Exception e) {
			this.fail("Unable to close the previous BMS Modbus server: " + e.getMessage(), e);
			return false;
		}
	}

	@Override
	public RuntimeState runtimeState() {
		return this.runtimeState;
	}

	@Override
	public String runtimeError() {
		return this.runtimeError;
	}

	Map<Integer, BmsMonitoringSnapshot> monitoringFrames() {
		var result = new HashMap<Integer, BmsMonitoringSnapshot>();
		var ambiguous = new java.util.HashSet<Integer>();
		this.monitoringProviders.forEach((provider, index) -> {
			if (result.containsKey(index)) {
				ambiguous.add(index);
				return;
			}
			var frame = provider.latestFrame();
			if (frame != null && !provider.stringKey().equals(frame.stringKey())) {
				ambiguous.add(index);
				return;
			}
			result.put(index, frame);
		});
		ambiguous.forEach(result::remove);
		return result;
	}
}
