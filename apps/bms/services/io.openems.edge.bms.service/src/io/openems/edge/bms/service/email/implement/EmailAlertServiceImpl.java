package io.openems.edge.bms.service.email.implement;

import io.openems.edge.bms.service.email.implement.persistence.EmailAlertEventRepository;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;
import io.openems.edge.bms.service.email.contracts.EmailTransport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.bms.service.calculation.contracts.BmsStringCalculator;
import io.openems.edge.bms.service.strings.contracts.StringRepository;

/**
 * Keeps SMTP credentials in the protected ConfigAdmin store. The JSON API only
 * ever receives the presence bit; neither this component nor the API logs a
 * password or an SMTP server response.
 */
@Component(name = "io.openems.edge.bms.platform.email.EmailAlertServiceImpl", service = EmailAlertService.class, immediate = true)
public class EmailAlertServiceImpl implements EmailAlertService {
	static final String PID = "io.openems.edge.bms.platform.email";
	private static final Logger LOG = LoggerFactory.getLogger(EmailAlertServiceImpl.class);
	private static final List<String> SUPPORTED_CATEGORIES = List.of("missing-stale-data", "high-resistance", "high-temperature", "low-voltage", "high-voltage");
	private final Map<String, Long> activeEvents = new ConcurrentHashMap<>();
	private final Map<String, DeliveryStatus> deliveryStatus = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<BmsStringCalculator> calculators = new CopyOnWriteArrayList<>();

	@Reference
	private ConfigurationAdmin configurationAdmin;
	@Reference
	private EmailAlertEventRepository events;
	@Reference
	private StringRepository strings;
	@Reference
	private EmailTransport transport;
	private volatile Settings settings = defaults();
	private volatile String password = "";
	private Timer evaluator;

	@Activate
	protected void activate() {
		this.reload();
		this.reloadDeliveryState();
		this.evaluator = new Timer("maxiems-email-evaluator", true);
		this.evaluator.scheduleAtFixedRate(new TimerTask() {
			@Override public void run() { evaluateSnapshots(); }
		}, 15_000L, 15_000L);
	}

	@org.osgi.service.component.annotations.Deactivate
	protected synchronized void deactivate() { if (this.evaluator != null) this.evaluator.cancel(); }

	@Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	protected void addCalculator(BmsStringCalculator calculator) { this.calculators.addIfAbsent(calculator); }
	protected void removeCalculator(BmsStringCalculator calculator) { this.calculators.remove(calculator); }

	@Override
	public Settings getSettings() {
		return this.settings;
	}

	@Override
	public synchronized Settings save(Settings requested, String newPassword) {
		var nextPassword = newPassword == null ? this.password : newPassword;
		validate(requested, nextPassword);
		try {
			Configuration configuration = this.configurationAdmin.getConfiguration(PID, "?");
			Dictionary<String, Object> values = new Hashtable<>();
			values.put("enabled", requested.enabled()); values.put("host", requested.host()); values.put("port", requested.port());
			values.put("security", requested.security()); values.put("username", requested.username()); values.put("password", nextPassword);
			values.put("senderAddress", requested.senderAddress()); values.put("senderName", requested.senderName());
			values.put("recipients", requested.recipients().toArray(String[]::new));
			values.put("categories", requested.categories().toArray(String[]::new)); values.put("sendRecovery", requested.sendRecovery());
			values.put("reminderMinutes", requested.reminderMinutes()); configuration.update(values);
			this.password = nextPassword;
			this.settings = view(requested, nextPassword);
			return this.settings;
		} catch (Exception e) {
			LOG.warn("Unable to save email alert settings: {}", safeError(e));
			throw new IllegalStateException("Email settings could not be saved");
		}
	}

	@Override
	public String sendTest(String subject, String body) {
		var current = this.settings;
		validateTestMessage(subject, body);
		try {
			this.transport.send(current, this.password, subject, body);
			return "Test email accepted by the configured relay";
		} catch (Exception e) {
			LOG.warn("Email test delivery failed: {}", safeError(e));
			throw new IllegalStateException("The relay could not accept the test email. Check host, port, security, and credentials.");
		}
	}

	@Override
	public List<DeliveryStatus> getDeliveryStatus() {
		return this.deliveryStatus.values().stream().sorted((left, right) -> Long.compare(right.updatedAt(), left.updatedAt())).toList();
	}

	/** Called by future snapshot wiring; state remains here so delivery is deduplicated. */
	void evaluate(String identity, boolean active, String subject, String body) {
		var current = this.settings;
		var category = identity.substring(0, identity.indexOf(':'));
		if (!current.enabled() || !current.categories().contains(category)) return;
		var now = System.currentTimeMillis(); var previous = this.activeEvents.get(identity);
		if (active && (previous == null || now - previous >= current.reminderMinutes() * 60_000L)) {
			this.activeEvents.put(identity, now); updateStatus(identity, true, now, "PENDING"); deliverAsync(identity, current, subject, body);
		} else if (!active && previous != null) {
			this.activeEvents.remove(identity);
			updateStatus(identity, false, previous, "RECOVERED");
			if (current.sendRecovery()) deliverAsync(identity, current, "Recovered: " + subject, body);
		}
	}

	private void evaluateSnapshots() {
		var now = System.currentTimeMillis();
		for (var calculator : this.calculators) {
			try {
				var snapshot = calculator.latestSnapshot();
				var unavailable = hasUnavailableData(snapshot, now);
				var identity = "missing-stale-data:" + calculator.id();
				evaluate(identity, unavailable, "BMS data unavailable for " + calculator.id(),
						snapshot == null || now - snapshot.capturedAt() >= 60_000L ? "The latest BMS snapshot is missing or older than 60 seconds." : "A BMS cell reported partial or unavailable data.");
				// Retained values in stale or partial snapshots are diagnostics, not fresh
				// measurements. Keep any existing threshold event active until a complete
				// snapshot can prove recovery, but do not create false threshold alerts.
				if (snapshot != null && !unavailable) evaluateThresholds(calculator, snapshot);
			} catch (Exception e) {
				// A calculator disappearing or failing must never affect polling or other calculators.
				LOG.debug("Skipping email evaluation for a BMS calculator: {}", safeError(e));
			}
		}
	}
	static boolean hasUnavailableData(io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot snapshot, long now) {
		return snapshot == null || now - snapshot.capturedAt() >= 60_000L || snapshot.stringWarning() != null
				|| snapshot.cells().values().stream().anyMatch(cell -> cell.availabilityWarning() != null);
	}

	private void evaluateThresholds(BmsStringCalculator calculator, io.openems.edge.bms.service.calculation.model.BmsCalculationSnapshot snapshot) throws java.sql.SQLException {
		var matcher = java.util.regex.Pattern.compile("str(\\d+)").matcher(calculator.id());
		if (!matcher.find()) return;
		var definition = this.strings.findByIndex(Integer.parseInt(matcher.group(1))).orElse(null);
		if (definition == null) return;
		for (var cell : snapshot.cells().values()) {
			evaluate(thresholdIdentity("high-resistance", definition.stringKey(), cell.cellId()), above(cell.internalResistance(), definition.highResistanceThreshold()), "High internal resistance on " + definition.displayName(), "Cell " + cell.cellId() + " exceeded its configured internal-resistance threshold.");
			evaluate(thresholdIdentity("high-temperature", definition.stringKey(), cell.cellId()), above(cell.temperature(), definition.highTemperatureThreshold()), "High temperature on " + definition.displayName(), "Cell " + cell.cellId() + " exceeded its configured temperature threshold.");
			evaluate(thresholdIdentity("low-voltage", definition.stringKey(), cell.cellId()), below(cell.voltage(), definition.lowVoltageThreshold()), "Low voltage on " + definition.displayName(), "Cell " + cell.cellId() + " fell below its configured voltage threshold.");
			evaluate(thresholdIdentity("high-voltage", definition.stringKey(), cell.cellId()), above(cell.voltage(), definition.highVoltageThreshold()), "High voltage on " + definition.displayName(), "Cell " + cell.cellId() + " exceeded its configured voltage threshold.");
		}
	}
	static String thresholdIdentity(String category, String stringKey, int cellId) { return category + ":" + stringKey + ":cell" + cellId; }
	private static boolean above(Number value, double threshold) { return value != null && threshold > 0 && value.doubleValue() > threshold; }
	private static boolean below(Number value, double threshold) { return value != null && threshold > 0 && value.doubleValue() < threshold; }

	private void deliverAsync(String identity, Settings current, String subject, String body) {
		new Thread(() -> deliver(identity, current, subject, body), "maxiems-email-alert").start();
	}

	/** Kept synchronous for deterministic testing; callers use {@link #deliverAsync}. */
	void deliver(String identity, Settings current, String subject, String body) {
		try { this.transport.send(current, this.password, subject, body); updateStatus(identity, this.activeEvents.containsKey(identity), this.activeEvents.getOrDefault(identity, 0L), "SENT"); }
		catch (Exception e) { updateStatus(identity, this.activeEvents.containsKey(identity), this.activeEvents.getOrDefault(identity, 0L), "FAILED"); LOG.warn("Email alert delivery failed: {}", safeError(e)); }
	}

	private void reloadDeliveryState() {
		try {
			for (var status : this.events.list()) {
				this.deliveryStatus.put(status.eventKey(), status);
				if (status.active() && status.lastSentAt() > 0) this.activeEvents.put(status.eventKey(), status.lastSentAt());
			}
		} catch (Exception e) { LOG.warn("Unable to load email delivery state: {}", safeError(e)); }
	}

	private void updateStatus(String eventKey, boolean active, long lastSentAt, String result) {
		var status = new DeliveryStatus(eventKey, active, lastSentAt, result, System.currentTimeMillis());
		this.deliveryStatus.put(eventKey, status);
		try { this.events.save(status); } catch (Exception e) { LOG.warn("Unable to persist email delivery state: {}", safeError(e)); }
	}

	private void reload() {
		try {
			var properties = this.configurationAdmin.getConfiguration(PID, "?").getProperties();
			if (properties == null) return;
			this.password = string(properties, "password", "");
			this.settings = view(new Settings(bool(properties, "enabled"), string(properties, "host", ""), integer(properties, "port", 25),
					normalizeSecurity(string(properties, "security", "plain")), string(properties, "username", ""), !this.password.isEmpty(),
					string(properties, "senderAddress", ""), string(properties, "senderName", ""), strings(properties, "recipients"),
					strings(properties, "categories"), bool(properties, "sendRecovery"), integer(properties, "reminderMinutes", 60)), this.password);
		} catch (Exception e) { LOG.warn("Unable to load email alert settings: {}", safeError(e)); }
	}

	private static Settings defaults() { return new Settings(false, "", 25, "plain", "", false, "", "", List.of(), List.of(), true, 60); }
	static Settings view(Settings value, String secret) { return new Settings(value.enabled(), value.host(), value.port(), value.security(), value.username(), !secret.isEmpty(), value.senderAddress(), value.senderName(), List.copyOf(value.recipients()), List.copyOf(value.categories()), value.sendRecovery(), value.reminderMinutes()); }
	static void validate(Settings value, String password) {
		if (!host(value.host())) throw new IllegalArgumentException("SMTP host is invalid");
		if (value.port() < 1 || value.port() > 65535) throw new IllegalArgumentException("SMTP port must be between 1 and 65535");
		if (!List.of("plain", "starttls", "tls").contains(value.security())) throw new IllegalArgumentException("SMTP security mode is invalid");
		if (!value.username().isBlank() && password.isEmpty()) throw new IllegalArgumentException("SMTP password is required when a username is configured");
		if (!value.username().isBlank() && "plain".equals(value.security())) throw new IllegalArgumentException("SMTP authentication requires TLS or STARTTLS");
		if (!email(value.senderAddress()) || value.recipients().isEmpty() || value.recipients().size() > 50 || value.recipients().stream().anyMatch(address -> !email(address))) throw new IllegalArgumentException("Sender or recipient address is invalid");
		if (value.senderName().contains("\r") || value.senderName().contains("\n") || value.senderName().length() > 120) throw new IllegalArgumentException("Sender name is invalid");
		if (value.categories().stream().anyMatch(category -> !SUPPORTED_CATEGORIES.contains(category))) throw new IllegalArgumentException("One or more email event categories are unavailable");
		if (value.reminderMinutes() < 5 || value.reminderMinutes() > 10080) throw new IllegalArgumentException("Reminder interval must be between 5 minutes and 7 days");
	}
	private static void validateTestMessage(String subject, String body) {
		if (subject == null || subject.isBlank() || subject.length() > 200 || !headerValue(subject)) {
			throw new IllegalArgumentException("Test email subject is invalid");
		}
		if (body == null || body.isBlank() || body.length() > 10_000) {
			throw new IllegalArgumentException("Test email message must be between 1 and 10000 characters");
		}
	}
	private static boolean email(String value) { return value != null && value.length() <= 254 && value.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$") && !value.contains("\r") && !value.contains("\n"); }
	private static boolean host(String value) {
		return value != null && value.length() <= 253
				&& value.matches("(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$");
	}
	private static String safeError(Exception exception) { return exception instanceof SocketTimeoutException ? "connection timed out" : "connection or authentication error"; }
	private static String string(Dictionary<String, Object> values, String key, String fallback) { var value = values.get(key); return value instanceof String text ? text : fallback; }
	private static int integer(Dictionary<String, Object> values, String key, int fallback) { var value = values.get(key); return value instanceof Integer number ? number : fallback; }
	private static boolean bool(Dictionary<String, Object> values, String key) { return Boolean.TRUE.equals(values.get(key)); }
	private static List<String> strings(Dictionary<String, Object> values, String key) { var value = values.get(key); if (value instanceof String[] array) return List.of(array); if (value instanceof String text) return List.of(text); return List.of(); }
	private static String normalizeSecurity(String value) { return "none".equals(value) ? "plain" : value; }

	static void send(Settings settings, String password, String subject, String body) throws IOException {
		if (!headerValue(subject)) throw new IOException("SMTP message subject is invalid");
		try (Socket socket = openSocket(settings)) {
			Socket transport = socket;
			var reader = new BufferedReader(new InputStreamReader(transport.getInputStream(), UTF_8));
			var writer = new BufferedWriter(new OutputStreamWriter(transport.getOutputStream(), UTF_8));
			expect(reader); command(writer, reader, "EHLO maxiems");
			if ("starttls".equals(settings.security())) {
				command(writer, reader, "STARTTLS");
				transport = tlsSocket(socket, settings.host(), settings.port());
				reader = new BufferedReader(new InputStreamReader(transport.getInputStream(), UTF_8));
				writer = new BufferedWriter(new OutputStreamWriter(transport.getOutputStream(), UTF_8));
				command(writer, reader, "EHLO maxiems");
			}
			if (!settings.username().isBlank()) { command(writer, reader, "AUTH LOGIN"); command(writer, reader, java.util.Base64.getEncoder().encodeToString(settings.username().getBytes(UTF_8))); command(writer, reader, java.util.Base64.getEncoder().encodeToString(password.getBytes(UTF_8))); }
			command(writer, reader, "MAIL FROM:<" + settings.senderAddress() + ">"); for (var recipient : settings.recipients()) command(writer, reader, "RCPT TO:<" + recipient + ">");
			command(writer, reader, "DATA"); writer.write("From: " + settings.senderName() + " <" + settings.senderAddress() + ">\r\nTo: " + String.join(", ", settings.recipients()) + "\r\nSubject: " + subject + "\r\n\r\n" + body.replace("\r", "").replace("\n.", "\n..") + "\r\n.\r\n"); writer.flush(); expect(reader); command(writer, reader, "QUIT");
		}
	}
	private static boolean headerValue(String value) { return value != null && !value.contains("\r") && !value.contains("\n"); }
	private static Socket openSocket(Settings settings) throws IOException {
		if ("tls".equals(settings.security())) {
			var socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket();
			socket.connect(new InetSocketAddress(settings.host(), settings.port()), 10_000);
			socket.setSoTimeout(10_000);
			configureTls(socket);
			socket.startHandshake();
			return socket;
		}
		var socket = new Socket();
		socket.connect(new InetSocketAddress(settings.host(), settings.port()), 10_000);
		socket.setSoTimeout(10_000);
		return socket;
	}
	private static SSLSocket tlsSocket(Socket socket, String host, int port) throws IOException {
		var secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
		secure.setSoTimeout(10_000);
		configureTls(secure);
		secure.startHandshake();
		return secure;
	}
	private static void configureTls(SSLSocket socket) {
		var parameters = socket.getSSLParameters();
		parameters.setEndpointIdentificationAlgorithm("HTTPS");
		socket.setSSLParameters(parameters);
	}
	private static void command(BufferedWriter writer, BufferedReader reader, String command) throws IOException { writer.write(command + "\r\n"); writer.flush(); expect(reader); }
	private static void expect(BufferedReader reader) throws IOException { String reply; do { reply = reader.readLine(); if (reply == null || reply.length() < 3 || reply.charAt(0) >= '4') throw new IOException("SMTP rejected request"); } while (reply.length() > 3 && reply.charAt(3) == '-'); }
}
