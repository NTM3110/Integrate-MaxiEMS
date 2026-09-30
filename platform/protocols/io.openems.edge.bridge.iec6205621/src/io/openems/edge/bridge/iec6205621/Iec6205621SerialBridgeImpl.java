package io.openems.edge.bridge.iec6205621;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

import com.fazecast.jSerialComm.SerialPort;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Bridge.IEC62056-21.Serial", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class Iec6205621SerialBridgeImpl extends AbstractOpenemsComponent
		implements Iec6205621Bridge, OpenemsComponent {

	private final Object portLock = new Object();
	private final Iec6205621Worker worker = new Iec6205621Worker(this::executeTask);
	private volatile Config config;
	private volatile SerialPort port;

	public Iec6205621SerialBridgeImpl() {
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
		this.worker.activate(config.id() + "-serial");
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.closePort();
		this.applyConfig(config);
		this.worker.modified(config.id() + "-serial");
	}

	private void applyConfig(Config config) {
		validate(config);
		this.config = config;
	}

	private static void validate(Config config) {
		if (config.portName().isBlank()) {
			throw new IllegalArgumentException("Serial port is required");
		}
		if (config.baudRate() < 1 || config.dataBits() < 5 || config.dataBits() > 8) {
			throw new IllegalArgumentException("Invalid serial settings");
		}
		if (config.readTimeoutMs() < 1 || config.writeTimeoutMs() < 1) {
			throw new IllegalArgumentException("Serial timeouts must be positive");
		}
	}

	@Override
	public void addProtocol(String sourceId, Iec6205621Protocol protocol) {
		this.worker.addProtocol(sourceId, protocol);
	}

	@Override
	public void removeProtocol(String sourceId) {
		this.worker.removeProtocol(sourceId);
	}

	@Override
	public <T> CompletableFuture<T> execute(String sourceId, Iec6205621Transaction<T> transaction) {
		var task = new Iec6205621OneShotTask<T>(Priority.HIGH, transaction);
		if (!this.isEnabled()) {
			task.onExecutionFinished(System.currentTimeMillis(),
					new IllegalStateException("IEC 62056-21 bridge is disabled"));
			return task.future();
		}
		try {
			this.worker.submitOneShot(sourceId, task);
		} catch (RuntimeException e) {
			task.onExecutionFinished(System.currentTimeMillis(), e);
		}
		return task.future();
	}

	@Override
	public <T> CompletableFuture<T> execute(Iec6205621Transaction<T> transaction) {
		var task = new Iec6205621OneShotTask<T>(Priority.HIGH, transaction);
		if (!this.isEnabled()) {
			task.onExecutionFinished(System.currentTimeMillis(),
					new IllegalStateException("IEC 62056-21 bridge is disabled"));
			return task.future();
		}
		this.worker.submitCompatibilityOneShot(task);
		return task.future();
	}

	private void executeTask(Iec6205621Task task) throws Exception {
		try {
			task.execute(this.transport());
		} catch (Exception e) {
			this.closePort();
			throw e;
		}
	}

	private Iec6205621Transport transport() throws IOException {
		var serialPort = this.openPort();
		return new Iec6205621Transport() {
			@Override
			public void send(byte[] bytes) throws IOException {
				var output = serialPort.getOutputStream();
				output.write(bytes);
				output.flush();
			}

			@Override
			public int readByte(long deadlineNanos) throws IOException {
				var input = serialPort.getInputStream();
				while (System.nanoTime() < deadlineNanos) {
					if (Thread.currentThread().isInterrupted()) {
						throw new InterruptedIOException("Serial read interrupted");
					}
					if (input.available() > 0) {
						int value = input.read();
						if (value < 0) {
							throw new IOException("Serial device disconnected");
						}
						return value;
					}
					try {
						Thread.sleep(2);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new InterruptedIOException("Serial read interrupted");
					}
				}
				throw new SocketTimeoutException("Serial response deadline exceeded");
			}

			@Override
			public String name() {
				return serialPort.getSystemPortName();
			}
		};
	}

	private SerialPort openPort() throws IOException {
		synchronized (this.portLock) {
			if (!this.isEnabled()) {
				throw new IOException("IEC 62056-21 bridge is disabled");
			}
			if (this.port != null && this.port.isOpen()) {
				return this.port;
			}
			var next = SerialPort.getCommPort(this.config.portName());
			next.setComPortParameters(this.config.baudRate(), this.config.dataBits(), this.config.stopBits().value,
					this.config.parity().value);
			next.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, this.config.readTimeoutMs(),
					this.config.writeTimeoutMs());
			if (!next.openPort()) {
				throw new IOException("Unable to open serial port " + this.config.portName());
			}
			this.port = next;
			return next;
		}
	}

	private void closePort() {
		synchronized (this.portLock) {
			if (this.port != null) {
				this.port.closePort();
				this.port = null;
			}
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.worker.deactivate();
		this.closePort();
		super.deactivate();
	}

	@Override
	public String debugLog() {
		var current = this.port;
		return current != null && current.isOpen() ? "connected " + current.getSystemPortName() : "disconnected";
	}
}
