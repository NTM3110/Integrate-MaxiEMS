package io.openems.edge.notification.email;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import jakarta.mail.internet.InternetAddress;

@Designate(ocd = Config.class, factory = true)
@Component(name = "Edge.Email.Notification", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class EmailNotificationImpl extends AbstractOpenemsComponent
		implements EmailNotification, EmailNotificationService, OpenemsComponent {
	private static final long[] RETRY_DELAYS_SECONDS = { 30, 60, 120, 240 };

	private final Logger log = LoggerFactory.getLogger(EmailNotificationImpl.class);
	private final AtomicInteger pending = new AtomicInteger();
	private final AtomicLong sent = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private EmailSender sender;

	private volatile ScheduledExecutorService executor;
	private volatile Config config;

	public EmailNotificationImpl() {
		super(OpenemsComponent.ChannelId.values(), EmailNotification.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		validate(config);
		super.activate(context, config.id(), config.alias(), config.enabled());
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "sender", config.sender_id())) {
			return;
		}
		this.pending.set(0);
		this.config = config;
		this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
			var thread = new Thread(runnable, "openems-email-notification");
			thread.setDaemon(true);
			return thread;
		});
		this.updateChannels();
	}

	@Deactivate
	@Override
	protected void deactivate() {
		var current = this.executor;
		this.executor = null;
		if (current != null) {
			current.shutdownNow();
		}
		this.config = null;
		super.deactivate();
	}

	@Override
	public EnqueueResult enqueue(EmailMessage message) {
		Objects.requireNonNull(message);
		var currentConfig = this.config;
		var currentExecutor = this.executor;
		if (currentConfig == null || currentExecutor == null || currentExecutor.isShutdown()) {
			return EnqueueResult.SERVICE_UNAVAILABLE;
		}
		var count = this.pending.incrementAndGet();
		if (count > currentConfig.queueCapacity()) {
			this.pending.decrementAndGet();
			this.channel(EmailNotification.ChannelId.QUEUE_FULL).setNextValue(true);
			this.updateChannels();
			return EnqueueResult.QUEUE_FULL;
		}
		this.channel(EmailNotification.ChannelId.QUEUE_FULL).setNextValue(false);
		try {
			currentExecutor.execute(() -> this.trySend(message, 1));
		} catch (RuntimeException e) {
			this.pending.decrementAndGet();
			this.updateChannels();
			return EnqueueResult.SERVICE_UNAVAILABLE;
		}
		this.updateChannels();
		return EnqueueResult.ACCEPTED;
	}

	private void trySend(EmailMessage message, int attempt) {
		try {
			this.send(message);
			this.pending.decrementAndGet();
			this.sent.incrementAndGet();
			this.channel(EmailNotification.ChannelId.SEND_FAILED).setNextValue(false);
			this.channel(EmailNotification.ChannelId.LAST_ERROR).setNextValue("");
			this.channel(EmailNotification.ChannelId.LAST_SEND_TIMESTAMP).setNextValue(ZonedDateTime.now().toString());
		} catch (Exception e) {
			var currentConfig = this.config;
			var currentExecutor = this.executor;
			if (currentConfig != null && currentExecutor != null && attempt < currentConfig.maxAttempts()) {
				var delayIndex = Math.min(attempt - 1, RETRY_DELAYS_SECONDS.length - 1);
				currentExecutor.schedule(() -> this.trySend(message, attempt + 1),
						RETRY_DELAYS_SECONDS[delayIndex], TimeUnit.SECONDS);
			} else {
				this.pending.decrementAndGet();
				this.failed.incrementAndGet();
				this.channel(EmailNotification.ChannelId.SEND_FAILED).setNextValue(true);
			}
			this.channel(EmailNotification.ChannelId.LAST_ERROR).setNextValue(safeError(e));
			this.log.warn("SMTP send attempt " + attempt + " failed", e);
		} finally {
			this.updateChannels();
		}
	}

	private void send(EmailMessage message) throws Exception {
		var currentConfig = this.config;
		if (currentConfig == null) {
			throw new IllegalStateException("Email notification is not configured");
		}
		this.sender.send(message, currentConfig.to(), currentConfig.cc());
	}

	private static void validate(Config config) throws OpenemsNamedException {
		if (config.sender_id().isBlank() || config.to().length == 0
				|| Arrays.stream(config.to()).anyMatch(String::isBlank)
				|| Arrays.stream(config.cc()).anyMatch(String::isBlank)
				|| config.queueCapacity() < 1 || config.maxAttempts() < 1) {
			throw new OpenemsNamedException(OpenemsError.GENERIC, "Invalid SMTP configuration");
		}
		try {
			for (var address : config.to()) {
				new InternetAddress(address, true);
			}
			for (var address : config.cc()) {
				new InternetAddress(address, true);
			}
		} catch (Exception e) {
			throw new OpenemsNamedException(OpenemsError.GENERIC, "Invalid email address: " + safeError(e));
		}
	}

	private void updateChannels() {
		this.channel(EmailNotification.ChannelId.QUEUE_SIZE).setNextValue(this.pending.get());
		this.channel(EmailNotification.ChannelId.SENT_EMAIL_COUNT).setNextValue(this.sent.get());
		this.channel(EmailNotification.ChannelId.FAILED_EMAIL_COUNT).setNextValue(this.failed.get());
	}

	private static String safeError(Exception e) {
		var message = e.getMessage();
		return message == null ? e.getClass().getSimpleName() : message;
	}

	@Override
	public String debugLog() {
		return "Queue:" + this.pending.get() + " Sent:" + this.sent.get() + " Failed:" + this.failed.get();
	}
}
