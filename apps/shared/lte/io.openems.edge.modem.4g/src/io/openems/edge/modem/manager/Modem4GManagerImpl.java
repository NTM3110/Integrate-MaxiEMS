package io.openems.edge.modem.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.serialization.EmptyObject;
import io.openems.common.session.Role;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.EdgeGuards;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.modem.manager.ModemAtParser.AtStatus;
import io.openems.edge.modem.manager.jsonrpc.GetModemConfig;
import io.openems.edge.modem.manager.jsonrpc.GetModemStatus;
import io.openems.edge.modem.manager.jsonrpc.SetModemConfig;

@Designate(ocd = Config.class, factory = true)
@Component(
		name = "Modem.4G.Manager",
		immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class Modem4GManagerImpl extends AbstractOpenemsComponent
		implements Modem4GManager, OpenemsComponent, ComponentJsonApi {

	private static final String IP_ADDR_COMMAND = "ip -o -4 addr show up scope global";
	private static final String PORT_DISCOVERY_COMMAND = "for f in /dev/ttyUSB* /dev/ttyACM*; do "
			+ "[ -e \"$f\" ] && printf '%s\\n' \"$f\"; done";
	private static final long COMMAND_TIMEOUT_SECONDS = 30;
	private static final long SET_CONFIG_TIMEOUT_SECONDS = 45;

	private record ModemSettings(String apn, String username, String password) {
	}

	private final Logger log = LoggerFactory.getLogger(Modem4GManagerImpl.class);
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final ModemMonitoringLifecycle monitoringLifecycle = new ModemMonitoringLifecycle();

	@Reference
	private ConfigurationAdmin configurationAdmin;

	private String cachedAtPort = "";
	private volatile ModemSettings settings = new ModemSettings("", "", "");
	private final ModemEcmStartTracker ecmStartTracker = new ModemEcmStartTracker();
	private ScheduledFuture<?> monitoringFuture;

	public Modem4GManagerImpl() {
		super(OpenemsComponent.ChannelId.values(), Modem4GManager.ChannelId.values());
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.applyConfigFields(config);
		this.applyMonitoringEnabled(config.enabled());
		this.logInfo(this.log, "4G Modem Manager activated. APN: " + config.apn());
	}

	@Modified
	void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.applyConfigFields(config);
		var changed = this.applyMonitoringEnabled(config.enabled());
		this.executor.submit(() -> {
			this.ecmStartTracker.reset();
			if (shouldSubmitModifiedUpdate(config) && !changed) {
				this.updateModemStatus();
			}
		});
	}

	static boolean shouldSubmitModifiedUpdate(Config config) {
		return config.enabled();
	}

	@Deactivate
	protected void deactivate() {
		this.cancelMonitoring();
		this.executor.shutdownNow();
		super.deactivate();
	}

	private boolean applyMonitoringEnabled(boolean enabled) {
		var changed = this.monitoringLifecycle.applyEnabled(enabled);
		if (!changed) {
			return false;
		}
		if (enabled) {
			this.monitoringFuture = this.executor.scheduleWithFixedDelay(() -> {
				try {
					this.updateModemStatus();
				} catch (Exception e) {
					this.logWarn(this.log, "Error in modem monitor: " + e.getMessage());
				}
			}, 1, 10, TimeUnit.SECONDS);
		} else {
			this.cancelMonitoring();
		}
		return true;
	}

	private void cancelMonitoring() {
		if (this.monitoringFuture != null) {
			this.monitoringFuture.cancel(false);
			this.monitoringFuture = null;
		}
		this.monitoringLifecycle.markStopped();
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(new GetModemStatus(), endpoint -> {
			endpoint.setDescription("Gets current 4G modem status.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.GUEST));
		}, call -> this.handleGetModemStatus());
		builder.handleRequest(new GetModemConfig(), endpoint -> {
			endpoint.setDescription("Gets current 4G modem APN configuration.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.OWNER));
		}, call -> this.handleGetModemConfig());
		builder.handleRequest(new SetModemConfig(), endpoint -> {
			endpoint.setDescription("Sets 4G modem APN configuration.");
			endpoint.setGuards(EdgeGuards.roleIsAtleast(Role.ADMIN));
		}, call -> {
			this.handleSetModemConfig(call.getRequest());
			return EmptyObject.INSTANCE;
		});
	}

	private void applyConfigFields(Config config) {
		this.settings = new ModemSettings(config.apn(), config.username(), config.password());
	}

	private void updateModemStatus() {
		try {
			var atOutput = this.executeAtCommandBatch();
			var atStatus = ModemAtParser.parse(atOutput);
			if (this.ensureEc200Ecm(atStatus)) {
				atOutput = this.executeAtCommandBatch();
				atStatus = ModemAtParser.parse(atOutput);
				this.ecmStartTracker.afterImmediateRequery(atStatus);
			}
			var ip = this.getModemIpAddress();
			this.updateChannels(atStatus, ip);
		} catch (Exception e) {
			this.channel(Modem4GManager.ChannelId.LAST_ERROR).setNextValue(e.getMessage());
			this.logWarn(this.log, "Modem status update failed: " + e.getMessage());
		}
	}

	private void updateChannels(AtStatus status, String ip) {
		var hasIp = !ip.isEmpty();
		this.channel(Modem4GManager.ChannelId.IP_ADDRESS).setNextValue(hasIp ? ip : "Not connected");
		this.channel(Modem4GManager.ChannelId.SIGNAL_STRENGTH).setNextValue(status.signalPercent());
		this.channel(Modem4GManager.ChannelId.SIGNAL_DBM).setNextValue(status.signalDbm());
		this.channel(Modem4GManager.ChannelId.IMEI).setNextValue(status.imei());
		this.channel(Modem4GManager.ChannelId.OPERATOR_NAME).setNextValue(status.operatorName());
		this.channel(Modem4GManager.ChannelId.CONNECTION_TYPE).setNextValue(status.connectionType());
		this.channel(Modem4GManager.ChannelId.MODEM_MODEL).setNextValue(status.model());
		this.channel(Modem4GManager.ChannelId.SIM_STATUS).setNextValue(status.simStatus());
		this.channel(Modem4GManager.ChannelId.CURRENT_APN).setNextValue(this.settings.apn());
		var hasAtResponse = ModemAtParser.hasStatusEvidence(status);
		this.channel(Modem4GManager.ChannelId.NO_MODEM_DETECTED).setNextValue(!hasIp && !hasAtResponse);
		var connected = status.isEc200() ? hasIp && status.isEcmActive() : hasIp;
		var modemStatus = connected ? ModemStatus.CONNECTED : hasAtResponse ? ModemStatus.DISCONNECTED : ModemStatus.NO_MODEM;
		this.channel(Modem4GManager.ChannelId.MODEM_STATUS).setNextValue(modemStatus.name());
		this.channel(Modem4GManager.ChannelId.LAST_ERROR).setNextValue("");
	}

	private boolean ensureEc200Ecm(AtStatus status) throws IOException, InterruptedException {
		if (!this.ecmStartTracker.shouldStart(status)) {
			return false;
		}
		this.startEc200Ecm();
		this.ecmStartTracker.markStartAttempted();
		return true;
	}

	private void startEc200Ecm() throws IOException, InterruptedException {
		var port = this.requireAtPort();
		var settings = this.settings;
		var command = ModemCommandBuilder.buildAtBatchCommand(port,
				ModemCommandBuilder.ec200ConfigureAndStartCommands(settings.apn(), settings.username(), settings.password()),
				true);
		this.executeCommand(command, true);
	}

	private GetModemStatus.Response handleGetModemStatus() {
		var status = this.getModemStatus();
		return new GetModemStatus.Response(status.status(), status.signalStrength(), status.signalDbm(),
				status.operator(), status.ipAddress(), status.connectionType(), status.modemModel(), status.imei(),
				status.lastError());
	}

	@Override
	public Status getModemStatus() {
		return new Status(this.getChannelString(Modem4GManager.ChannelId.MODEM_STATUS),
				this.getChannelInt(Modem4GManager.ChannelId.SIGNAL_STRENGTH),
				this.getChannelInt(Modem4GManager.ChannelId.SIGNAL_DBM),
				this.getChannelString(Modem4GManager.ChannelId.OPERATOR_NAME),
				this.getChannelString(Modem4GManager.ChannelId.IP_ADDRESS),
				this.getChannelString(Modem4GManager.ChannelId.CONNECTION_TYPE),
				this.getChannelString(Modem4GManager.ChannelId.MODEM_MODEL),
				this.getChannelString(Modem4GManager.ChannelId.IMEI),
				this.getChannelString(Modem4GManager.ChannelId.LAST_ERROR));
	}

	private GetModemConfig.Response handleGetModemConfig() {
		var settings = this.settings;
		return new GetModemConfig.Response(settings.apn(), settings.username(), "");
	}

	@Override
	public Settings getModemConfig() {
		var settings = this.settings;
		return new Settings(settings.apn(), settings.username(), !settings.password().isBlank());
	}

	private void handleSetModemConfig(SetModemConfig.Request request) throws OpenemsException {
		this.setModemConfig(request.apn(), request.username(), request.password());
	}

	@Override
	public void setModemConfig(String apn, String username, String password) throws OpenemsException {
		var effectivePassword = password == null || password.isBlank() ? this.settings.password() : password;
		var future = this.executor.submit(() -> {
			this.applySetModemConfig(new SetModemConfig.Request(apn, username, effectivePassword));
			return null;
		});
		try {
			future.get(SET_CONFIG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			throw new OpenemsException("Failed to set modem config: operation timed out");
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new OpenemsException("Failed to set modem config: operation interrupted");
		} catch (ExecutionException e) {
			var cause = e.getCause();
			throw new OpenemsException("Failed to set modem config: " + cause.getMessage());
		}
		try {
			this.persistConfig(apn, username, effectivePassword);
		} catch (Exception e) {
			throw new OpenemsException("Modem was configured, but its settings could not be persisted: " + e.getMessage());
		}
	}

	private void persistConfig(String apn, String username, String password) throws Exception {
		var configurations = this.configurationAdmin.listConfigurations("(service.factoryPid=Modem.4G.Manager)");
		if (configurations == null) {
			throw new IOException("Modem.4G.Manager ConfigAdmin record is missing");
		}
		for (var configuration : configurations) {
			var properties = configuration.getProperties();
			if (properties == null || !this.id().equals(properties.get("id"))) {
				continue;
			}
			Dictionary<String, Object> updated = new Hashtable<>();
			var keys = properties.keys();
			while (keys.hasMoreElements()) {
				var key = keys.nextElement();
				if (!key.startsWith("service.")) {
					updated.put(key, properties.get(key));
				}
			}
			updated.put("apn", apn);
			updated.put("username", username);
			updated.put("password", password);
			configuration.update(updated);
			return;
		}
		throw new IOException("ConfigAdmin record for " + this.id() + " is missing");
	}

	private void applySetModemConfig(SetModemConfig.Request request) throws IOException, InterruptedException {
		var apn = request.apn();
		var username = request.username();
		var password = request.password();
		var status = ModemAtParser.parse(this.executeAtCommandBatch());
		if (status.isEc200()) {
			if (!status.isSimReady()) {
				var simStatus = status.simStatus().isBlank() ? "UNKNOWN" : status.simStatus();
				throw new IOException("SIM is not ready: " + simStatus);
			}
			this.ecmStartTracker.reset();
			this.startEc200Ecm(apn, username, password);
		} else {
			this.updatePppScript(apn, username, password);
		}
		this.settings = new ModemSettings(apn, username, password);
		this.updateModemStatus();
	}

	private void startEc200Ecm(String apn, String username, String password) throws IOException, InterruptedException {
		var port = this.requireAtPort();
		var command = ModemCommandBuilder.buildAtBatchCommand(port,
				ModemCommandBuilder.ec200ConfigureAndStartCommands(apn, username, password), true);
		this.executeCommand(command, true);
	}

	private void updatePppScript(String apn, String username, String password) throws IOException, InterruptedException {
		this.executeCommand(ModemCommandBuilder.buildPppConfigCommand(apn, username, password), true);
	}

	private String getChannelString(Modem4GManager.ChannelId channelId) {
		var val = this.channel(channelId).value().get();
		return val != null ? val.toString() : "";
	}

	private int getChannelInt(Modem4GManager.ChannelId channelId) {
		var val = this.channel(channelId).value().get();
		return val instanceof Number number ? number.intValue() : 0;
	}

	private String executeAtCommandBatch() {
		for (var port : this.atPortCandidates()) {
			try {
					var output = this.executeCommand(ModemCommandBuilder.buildAtBatchCommand(port,
							ModemCommandBuilder.statusCommands(), false), false);
				if (ModemAtParser.hasStatusEvidence(output)) {
					this.cachedAtPort = port;
					return output;
				}
			} catch (Exception e) {
				this.logDebug(this.log, "AT batch failed on " + port + ": " + e.getMessage());
			}
		}
		this.cachedAtPort = "";
		return "";
	}

	private String requireAtPort() throws IOException {
		if (ModemNetworkDiscovery.isAtPortCandidate(this.cachedAtPort)) {
			return this.cachedAtPort;
		}
		var output = this.executeAtCommandBatch();
		if (output.isBlank() || this.cachedAtPort.isBlank()) {
			throw new IOException("No usable Quectel AT port found");
		}
		return this.cachedAtPort;
	}

	private List<String> atPortCandidates() {
		var ports = new ArrayList<String>();
		if (ModemNetworkDiscovery.isAtPortCandidate(this.cachedAtPort)) {
			ports.add(this.cachedAtPort);
		}
		try {
			ports.addAll(this.executeCommand(new CommandSpec(PORT_DISCOVERY_COMMAND), false).lines().toList());
		} catch (Exception e) {
			this.logDebug(this.log, "AT port discovery failed: " + e.getMessage());
		}
		ports.add("/dev/ttyUSB2");
		var sorted = new ArrayList<>(ModemNetworkDiscovery.sortAtPortCandidates(ports));
		if (ModemNetworkDiscovery.isAtPortCandidate(this.cachedAtPort) && sorted.remove(this.cachedAtPort)) {
			sorted.add(0, this.cachedAtPort);
		}
		return sorted;
	}

	private String getModemIpAddress() {
		try {
			return ModemNetworkDiscovery.firstModemIp(this.executeCommand(new CommandSpec(IP_ADDR_COMMAND), false));
		} catch (Exception e) {
			return "";
		}
	}

	private String executeCommand(CommandSpec command, boolean failOnNonZero) throws IOException, InterruptedException {
		var builder = new ProcessBuilder("bash", "-c", command.shellCommand());
		builder.redirectErrorStream(true);
		var process = builder.start();
		var outputFuture = CompletableFuture.supplyAsync(() -> process.inputReader(StandardCharsets.UTF_8).lines()
				.collect(java.util.stream.Collectors.joining("\n")));
		final boolean finished;
		try {
			try (var stdin = process.getOutputStream()) {
				stdin.write(command.stdin());
			}
			finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			destroyProcess(process);
			outputFuture.cancel(true);
			Thread.currentThread().interrupt();
			throw e;
		} catch (IOException e) {
			destroyProcess(process);
			outputFuture.cancel(true);
			throw e;
		}
		if (!finished) {
			destroyProcess(process);
			try {
				outputFuture.get(1, TimeUnit.SECONDS);
			} catch (Exception e) {
				outputFuture.cancel(true);
			}
			throw new IOException("Command timed out");
		}
		var output = "";
		try {
			output = outputFuture.get(1, TimeUnit.SECONDS).trim();
		} catch (Exception e) {
			outputFuture.cancel(true);
		}
		if (process.exitValue() != 0 && failOnNonZero) {
			throw new IOException("Command failed with exit code " + process.exitValue());
		}
		return output;
	}

	private static void destroyProcess(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}
}
