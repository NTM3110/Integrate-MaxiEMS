package io.openems.edge.controller.alarm.threshold;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.notification.email.EmailMessage;
import io.openems.edge.notification.email.EmailNotificationService;
import io.openems.edge.notification.email.EnqueueResult;

public class DummyEmailService extends AbstractOpenemsComponent implements EmailNotificationService {
	private final List<EmailMessage> messages = new ArrayList<>();

	public DummyEmailService() {
		super(OpenemsComponent.ChannelId.values());
		super.activate(null, "email0", "", true);
	}

	@Override
	public EnqueueResult enqueue(EmailMessage message) {
		this.messages.add(message);
		return EnqueueResult.ACCEPTED;
	}

	public List<EmailMessage> messages() {
		return this.messages;
	}
}
