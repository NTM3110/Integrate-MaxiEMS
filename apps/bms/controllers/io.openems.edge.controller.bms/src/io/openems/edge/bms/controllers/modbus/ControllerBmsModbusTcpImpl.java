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

import io.openems.edge.bms.service.monitoring.contracts.BmsMonitoringSnapshotProvider;
import io.openems.edge.bms.service.monitoring.model.BmsMonitoringSnapshot;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

/** Read-only Modbus/TCP projections of one immutable monitoring snapshot per request. */
@Designate(ocd = Config.class, factory = true)
@Component(name = "Controller.Bms.ModbusTcp", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerBmsModbusTcpImpl extends AbstractOpenemsComponent implements OpenemsComponent, Controller {
	private static final Logger LOG = LoggerFactory.getLogger(ControllerBmsModbusTcpImpl.class);
	private final Map<BmsMonitoringSnapshotProvider, Integer> monitoringProviders = new ConcurrentHashMap<>();
	private final BmsModbusProcessImage processImage = new BmsModbusProcessImage(this::monitoringFrames);
	private ModbusSlave slave;

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
		this.stop();
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
		if (matcher.matches()) this.monitoringProviders.put(provider, Integer.parseInt(matcher.group(1)));
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
		if (!config.enabled()) return;
		if (config.port() < 1 || config.port() > 65535 || config.unitId() < 1 || config.unitId() > 247) {
			LOG.error("BMS Modbus/TCP server has invalid port [{}] or unit-ID [{}]", config.port(), config.unitId());
			return;
		}
		try {
			this.slave = ModbusSlaveFactory.createTCPSlave(null, config.port(), 10, false, 60);
			this.slave.addProcessImage(config.unitId(), this.processImage);
			this.slave.open();
			LOG.info("BMS Modbus/TCP server listening on port {} with unit-ID {}", config.port(), config.unitId());
		} catch (Exception e) {
			LOG.error("Unable to bind BMS Modbus/TCP server on port " + config.port(), e);
			this.slave = null;
		}
	}

	private void stop() {
		if (this.slave == null) return;
		try {
			this.slave.close();
		} catch (Exception e) {
			LOG.warn("Unable to close BMS Modbus/TCP server", e);
		} finally {
			this.slave = null;
		}
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
