package io.openems.edge.notification.email;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import org.junit.Test;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;

public class EmailSenderImplTest {
	@Test
	public void testMultipartSerializationDoesNotRequireContextClassLoader() throws Exception {
		var session = Session.getInstance(new Properties());
		var from = new InternetAddress("sender@example.com");
		var to = new InternetAddress[] { new InternetAddress("recipient@example.com") };
		var message = new EmailMessage("Alarm", "Nội dung chữ", "<strong>Nội dung HTML</strong>");

		var thread = Thread.currentThread();
		var previousContextClassLoader = thread.getContextClassLoader();
		var output = new ByteArrayOutputStream();
		try {
			thread.setContextClassLoader(new ClassLoader(null) {
			});
			var mail = EmailSenderImpl.createMimeMessage(session, from, to, new InternetAddress[0], message);
			mail.writeTo(output);
		} finally {
			thread.setContextClassLoader(previousContextClassLoader);
		}

		var serialized = output.toString(StandardCharsets.US_ASCII);
		assertTrue(serialized.contains("Content-Type: multipart/alternative;"));
		assertTrue(serialized.contains("Content-Type: text/plain; charset=UTF-8"));
		assertTrue(serialized.contains("Content-Type: text/html; charset=UTF-8"));
		assertTrue(serialized.contains(Base64.getEncoder()
				.encodeToString(message.textBody().getBytes(StandardCharsets.UTF_8))));
	}
}
