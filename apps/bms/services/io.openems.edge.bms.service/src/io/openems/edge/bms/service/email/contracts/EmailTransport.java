package io.openems.edge.bms.service.email.contracts;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;

import java.io.IOException;

public interface EmailTransport {
	void send(EmailAlertService.Settings settings, String password, String subject, String body) throws IOException;
}
