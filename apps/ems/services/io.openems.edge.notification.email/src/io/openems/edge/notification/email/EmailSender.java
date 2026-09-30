package io.openems.edge.notification.email;

import io.openems.edge.common.component.OpenemsComponent;

/** SMTP sender used by one or more email notification components. */
public interface EmailSender extends OpenemsComponent {
	/**
	 * Sends an email immediately.
	 *
	 * @param message the email content
	 * @param to      recipient addresses
	 * @param cc      carbon-copy recipient addresses
	 * @throws Exception on address, connection or SMTP errors
	 */
	public void send(EmailMessage message, String[] to, String[] cc) throws Exception;
}
