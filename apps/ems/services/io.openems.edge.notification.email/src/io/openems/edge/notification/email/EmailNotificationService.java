package io.openems.edge.notification.email;

import io.openems.edge.common.component.OpenemsComponent;

public interface EmailNotificationService extends OpenemsComponent {
	public EnqueueResult enqueue(EmailMessage message);
}
