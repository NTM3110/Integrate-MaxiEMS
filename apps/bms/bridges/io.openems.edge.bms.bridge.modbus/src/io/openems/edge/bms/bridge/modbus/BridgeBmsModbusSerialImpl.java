package io.openems.edge.bms.bridge.modbus;

import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.msg.ModbusMessage;
import com.ghgande.j2mod.modbus.io.ModbusSerialTransaction;
import com.ghgande.j2mod.modbus.io.ModbusSerialTransport;
import com.ghgande.j2mod.modbus.io.ModbusTransaction;
import com.ghgande.j2mod.modbus.net.AbstractSerialConnection;
import com.ghgande.j2mod.modbus.net.SerialConnection;
import com.ghgande.j2mod.modbus.io.AbstractSerialTransportListener;
import com.ghgande.j2mod.modbus.util.SerialParameters;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.AbstractModbusBridge;
import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge;
import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.BatchKey;
import io.openems.edge.bms.bridge.modbus.api.BmsModbusBatchBridge.CompletedBatch;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.BridgeModbusSerial;
import io.openems.edge.bridge.modbus.api.Config;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.Parity;
import io.openems.edge.bridge.modbus.api.Stopbit;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.startstop.StartStoppable;

/** BMS-specific serial bridge with one read-only acquisition batch per port. */
@Designate(ocd = ConfigBmsSerial.class, factory = true)
@Component(name = "Bridge.BmsModbus.Serial", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE,
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class BridgeBmsModbusSerialImpl extends AbstractModbusBridge implements BmsModbusBatchBridge,
		BridgeModbus, BridgeModbusSerial, OpenemsComponent, EventHandler, StartStoppable {
	private volatile String portName = "";
	private volatile int baudrate;
	private volatile int databits;
	private volatile Stopbit stopbits;
	private volatile Parity parity;
	private volatile int responseTimeoutMs = 100;
	private volatile boolean configuredEnabled;
	private SerialConnection connection;
	private final BmsModbusBatchWorker batchWorker = new BmsModbusBatchWorker(
			task -> task.execute(this),
			elements -> Stream.of(elements).forEach(element -> element.invalidate(this)),
			this::invalidateElementsAfterReadErrors,
			System::currentTimeMillis,
			this::acceptCompletedBatch,
			this::isAcknowledged);
	private final AtomicReference<CompletedBatch> pendingBatch = new AtomicReference<>();
	private final AtomicReference<BatchKey> acknowledgedBatch = new AtomicReference<>();
	private volatile CompletedBatch armedBatch;
	private volatile CompletedBatch completedBatch;

	public BridgeBmsModbusSerialImpl() {
		super(OpenemsComponent.ChannelId.values(), BridgeModbus.ChannelId.values(),
				BridgeModbusSerial.ChannelId.values(), StartStoppable.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, ConfigBmsSerial config) {
		this.applyConfig(config);
		super.activate(context, this.commonConfig(config));
	}

	@Modified
	private void modified(ComponentContext context, ConfigBmsSerial config) {
		this.closeModbusConnection();
		this.batchWorker.stop();
		this.applyConfig(config);
		this.pendingBatch.set(null);
		this.acknowledgedBatch.set(null);
		this.armedBatch = null;
		this.completedBatch = null;
		super.modified(context, this.commonConfig(config));
	}

	private Config commonConfig(ConfigBmsSerial config) {
		return new Config(config.id(), config.alias(), config.enabled(), config.logVerbosity(),
				config.invalidateElementsAfterReadErrors());
	}

	private void applyConfig(ConfigBmsSerial config) {
		if (config.responseTimeoutMs() < 10 || config.responseTimeoutMs() > 10_000) {
			throw new IllegalArgumentException("responseTimeoutMs must be between 10 and 10000 ms");
		}
		this.portName = config.portName();
		this.baudrate = config.baudRate();
		this.databits = config.databits();
		this.stopbits = config.stopbits();
		this.parity = config.parity();
		this.responseTimeoutMs = config.responseTimeoutMs();
		this.configuredEnabled = config.enabled();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
		this.pendingBatch.set(null);
		this.acknowledgedBatch.set(null);
		this.armedBatch = null;
		this.completedBatch = null;
	}

	@Override
	protected void activateWorker(String id) {
		this.batchWorker.start(id);
	}

	@Override
	protected void modifyWorker(String id) {
		this.batchWorker.start(id);
	}

	@Override
	protected void deactivateWorker() {
		this.closeModbusConnection();
		this.batchWorker.stop();
	}

	@Override
	public void addProtocol(String sourceId, ModbusProtocol protocol) {
		this.batchWorker.addProtocol(sourceId, protocol);
	}

	@Override
	public void removeProtocol(String sourceId) {
		this.batchWorker.removeProtocol(sourceId);
	}

	@Override
	public void retryModbusCommunication(String sourceId) {
		this.batchWorker.retry(sourceId);
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.configuredEnabled || !this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.beforeProcessImage();
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.afterProcessImage();
		default -> {
		}
		}
	}

	void acceptCompletedBatch(CompletedBatch batch) {
		if (!this.pendingBatch.compareAndSet(null, batch)) {
			throw new IllegalStateException("Previous BMS batch is still pending");
		}
	}

	private boolean isAcknowledged(CompletedBatch batch) {
		return batch.key().equals(this.acknowledgedBatch.get());
	}

	void beforeProcessImage() {
		// Arm only batches already finished before the process-image switch.
		this.armedBatch = this.pendingBatch.getAndSet(null);
	}

	void afterProcessImage() {
		if (this.armedBatch != null) {
			this.completedBatch = this.armedBatch;
			this.armedBatch = null;
		}
	}

	@Override
	public CompletedBatch getCompletedBatch() {
		return this.completedBatch;
	}

	@Override
	public String debugLog() {
		var failure = this.batchWorker.failure();
		if (failure != null) {
			return "BMS batch worker failed: " + failure;
		}
		if (this.batchWorker.waitingForAck()) {
			var batch = this.completedBatch;
			return "BMS batch waiting for calculator ACK: "
					+ (batch == null ? "pending process-image switch" : batch.key());
		}
		return super.debugLog();
	}

	@Override
	public boolean acknowledgeProcessed(String generation, long batchId) {
		var visible = this.completedBatch;
		if (visible == null || !visible.key().equals(new BatchKey(generation, batchId))) {
			return false;
		}
		this.acknowledgedBatch.set(visible.key());
		return true;
	}

	@Override
	public boolean retryRequestImmediately() {
		return false;
	}

	@Override
	public synchronized void closeModbusConnection() {
		if (this.connection != null) {
			this.connection.close();
			this.connection = null;
		}
	}

	@Override
	public ModbusTransaction getNewModbusTransaction() throws OpenemsException {
		if (this.isStopped()) {
			this.closeModbusConnection();
			return null;
		}
		var transaction = new ModbusSerialTransaction(this.getModbusConnection());
		transaction.setRetries(0);
		return transaction;
	}

	private synchronized SerialConnection getModbusConnection() throws OpenemsException {
		if (this.connection == null) {
			var parameters = new SerialParameters();
			parameters.setPortName(this.portName);
			parameters.setBaudRate(this.baudrate);
			parameters.setDatabits(this.databits);
			parameters.setStopbits(this.stopbits.getValue());
			parameters.setParity(this.parity.getValue());
			parameters.setEncoding(Modbus.SERIAL_ENCODING_RTU);
			parameters.setEcho(false);
			parameters.disableRs485Control();
			this.connection = new SerialConnection(parameters);
		}
		if (!this.connection.isOpen()) {
			try {
				this.connection.open();
			} catch (Exception e) {
				throw new OpenemsException("Connection via [" + this.portName + "] failed: " + e.getMessage());
			}
			var transport = (ModbusSerialTransport) this.connection.getModbusTransport();
			transport.setTimeout(this.responseTimeoutMs);
			// Match the serial turnaround delay used by the shared OpenEMS bridge.
			transport.addListener(new AbstractSerialTransportListener() {
				@Override
				public void afterMessageWrite(AbstractSerialConnection port, ModbusMessage message) {
					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			});
		}
		return this.connection;
	}

	@Override
	public String getPortName() {
		return this.portName;
	}

	@Override
	public int getBaudrate() {
		return this.baudrate;
	}

	@Override
	public int getDatabits() {
		return this.databits;
	}

	@Override
	public Stopbit getStopbits() {
		return this.stopbits;
	}

	@Override
	public Parity getParity() {
		return this.parity;
	}
}
