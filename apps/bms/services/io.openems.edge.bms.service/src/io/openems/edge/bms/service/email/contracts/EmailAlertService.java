package io.openems.edge.bms.service.email.contracts;

import java.util.List;

/** Server-side, write-only SMTP settings and delivery boundary. */
public interface EmailAlertService {

	record Settings(boolean enabled, String host, int port, String security, String username,
			boolean passwordConfigured, String senderAddress, String senderName, List<String> recipients,
			List<String> categories, boolean sendRecovery, int reminderMinutes) {
	}
	record DeliveryStatus(String eventKey, boolean active, long lastSentAt, String lastDeliveryStatus, long updatedAt) {
	}

	Settings getSettings();
	Settings save(Settings settings, String password);
	String sendTest(String subject, String body);
	List<DeliveryStatus> getDeliveryStatus();
}
