package io.openems.edge.bms.service.email.implement;

import io.openems.edge.bms.service.email.contracts.EmailTransport;

import io.openems.edge.bms.service.email.contracts.EmailAlertService;

import java.io.IOException;

import org.osgi.service.component.annotations.Component;

@Component(name = "io.openems.edge.bms.platform.email.SmtpEmailTransport", service = EmailTransport.class)
public class SmtpEmailTransport implements EmailTransport {
	@Override
	public void send(EmailAlertService.Settings settings, String password, String subject, String body) throws IOException {
		EmailAlertServiceImpl.send(settings, password, subject, body);
	}
}
