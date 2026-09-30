package io.openems.edge.notification.email;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import jakarta.mail.Authenticator;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

@Designate(ocd = SenderConfig.class, factory = true)
@Component(name = "Edge.Email.Sender", immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class EmailSenderImpl extends AbstractOpenemsComponent implements EmailSender, OpenemsComponent {
	private volatile SenderConfig config;

	public EmailSenderImpl() {
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, SenderConfig config) throws OpenemsNamedException {
		validate(config);
		this.config = config;
		super.activate(context, config.id(), config.alias(), config.enabled());
	}

	@Deactivate
	@Override
	protected void deactivate() {
		this.config = null;
		super.deactivate();
	}

	@Override
	public void send(EmailMessage message, String[] to, String[] cc) throws Exception {
		Objects.requireNonNull(message);
		var currentConfig = this.config;
		if (currentConfig == null) {
			throw new MessagingException("Email sender is not configured");
		}
		var properties = new Properties();
		properties.put("mail.smtp.host", currentConfig.smtpHost());
		properties.put("mail.smtp.port", Integer.toString(currentConfig.smtpPort()));
		properties.put("mail.smtp.connectiontimeout", Integer.toString(currentConfig.connectionTimeoutMs()));
		properties.put("mail.smtp.timeout", Integer.toString(currentConfig.sendTimeoutMs()));
		properties.put("mail.smtp.writetimeout", Integer.toString(currentConfig.sendTimeoutMs()));
		properties.put("mail.smtp.auth", Boolean.toString(!currentConfig.username().isBlank()));
		properties.put("mail.smtp.starttls.enable",
				Boolean.toString(currentConfig.security() == SmtpSecurity.STARTTLS));
		properties.put("mail.smtp.starttls.required",
				Boolean.toString(currentConfig.security() == SmtpSecurity.STARTTLS));
		properties.put("mail.smtp.ssl.enable",
				Boolean.toString(currentConfig.security() == SmtpSecurity.SSL_TLS));

		Authenticator authenticator = null;
		if (!currentConfig.username().isBlank()) {
			authenticator = new Authenticator() {
				@Override
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(currentConfig.username(), currentConfig.password());
				}
			};
		}
		var session = Session.getInstance(properties, authenticator);
		var from = new InternetAddress(currentConfig.fromAddress(), currentConfig.fromName(),
				StandardCharsets.UTF_8.name());
		var mail = createMimeMessage(session, from, toAddresses(to), toAddresses(cc), message);
		try (var transport = session.getTransport("smtp")) {
			transport.connect();
			transport.sendMessage(mail, mail.getAllRecipients());
		}
	}

	static MimeMessage createMimeMessage(Session session, InternetAddress from, InternetAddress[] to,
			InternetAddress[] cc, EmailMessage message) throws Exception {
		/*
		 * Parse a complete RFC 822 message from bytes. MimeMessage can then copy the raw
		 * stream without reflective DataContentHandler discovery, which is unreliable
		 * across isolated OSGi bundle class loaders.
		 */
		var boundary = "----=_OpenEMS_" + UUID.randomUUID().toString().replace("-", "");
		var raw = new StringBuilder() //
				.append("From: ").append(from).append("\r\n") //
				.append("To: ").append(InternetAddress.toString(to)).append("\r\n");
		if (cc.length > 0) {
			raw.append("Cc: ").append(InternetAddress.toString(cc)).append("\r\n");
		}
		raw.append("Subject: ")
				.append(MimeUtility.encodeText(message.subject(), StandardCharsets.UTF_8.name(), "B"))
				.append("\r\n") //
				.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
				.append("\r\n") //
				.append("Message-ID: <").append(UUID.randomUUID()).append("@openems-edge>\r\n") //
				.append("MIME-Version: 1.0\r\n") //
				.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
		appendMimePart(raw, boundary, "text/plain", message.textBody());
		appendMimePart(raw, boundary, "text/html", message.htmlBody());
		raw.append("--").append(boundary).append("--\r\n");
		return new MimeMessage(session,
				new ByteArrayInputStream(raw.toString().getBytes(StandardCharsets.US_ASCII)));
	}

	private static void appendMimePart(StringBuilder body, String boundary, String mimeType, String content) {
		body.append("--").append(boundary).append("\r\n")
				.append("Content-Type: ").append(mimeType).append("; charset=UTF-8\r\n")
				.append("Content-Transfer-Encoding: base64\r\n\r\n")
				.append(Base64.getMimeEncoder(76, new byte[] { '\r', '\n' })
						.encodeToString(content.getBytes(StandardCharsets.UTF_8)))
				.append("\r\n");
	}

	private static InternetAddress[] toAddresses(String[] values) throws MessagingException {
		var result = new InternetAddress[values.length];
		for (var i = 0; i < values.length; i++) {
			result[i] = new InternetAddress(values[i], true);
		}
		return result;
	}

	private static void validate(SenderConfig config) throws OpenemsNamedException {
		if (config.smtpHost().isBlank() || config.smtpPort() < 1 || config.smtpPort() > 65_535
				|| config.fromAddress().isBlank() || config.connectionTimeoutMs() < 1 || config.sendTimeoutMs() < 1) {
			throw new OpenemsNamedException(OpenemsError.GENERIC, "Invalid email sender configuration");
		}
		try {
			new InternetAddress(config.fromAddress(), true);
		} catch (Exception e) {
			throw new OpenemsNamedException(OpenemsError.GENERIC, "Invalid sender address: " + safeError(e));
		}
	}

	private static String safeError(Exception e) {
		var message = e.getMessage();
		return message == null ? e.getClass().getSimpleName() : message;
	}

	@Override
	public String debugLog() {
		var currentConfig = this.config;
		return currentConfig == null ? "Not configured" : currentConfig.fromAddress();
	}
}
