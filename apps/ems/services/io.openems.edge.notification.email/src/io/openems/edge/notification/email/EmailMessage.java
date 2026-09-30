package io.openems.edge.notification.email;

import java.util.Objects;

/** Immutable message accepted by the Edge email service. */
public record EmailMessage(String subject, String textBody, String htmlBody) {
	public EmailMessage {
		Objects.requireNonNull(subject);
		Objects.requireNonNull(textBody);
		Objects.requireNonNull(htmlBody);
	}
}
