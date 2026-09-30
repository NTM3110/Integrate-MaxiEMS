package io.openems.edge.controller.alarm.threshold;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.Test;

public class AlarmEmailRendererTest {
	@Test
	public void testActivationAndRecoveryTemplateAndEscaping() {
		var time = ZonedDateTime.of(2026, 8, 24, 10, 30, 0, 0, ZoneId.of("Asia/Ho_Chi_Minh"));
		var message = AlarmEmailRenderer.render("Lộc Ninh 1 & 2", List.of(
				new AlarmTransition("wika1", "T35", AlarmType.PD, Compartment.IN, 0, 3, time, null, time, null),
				new AlarmTransition("wika2", "T<36>", AlarmType.TEMPERATURE, Compartment.OUT, 2, 0, time,
						"85.5 °C", time.minusHours(1).minusMinutes(2).minusSeconds(3),
						Duration.ofHours(1).plusMinutes(2).plusSeconds(3))));

		assertTrue(message.subject().equals("Cảnh báo từ Lộc Ninh 1 & 2 - 2 sự kiện cảnh báo"));
		assertTrue(message.textBody().contains("Mức cảnh báo: HIGH ACTIVITY"));
		assertTrue(message.textBody().contains("Giá trị sai lệch: 85.5 °C"));
		assertFalse(message.textBody().contains("Giá trị sự cố"));
		assertTrue(message.textBody().indexOf("Giá trị sai lệch") == message.textBody().lastIndexOf("Giá trị sai lệch"));
		assertTrue(message.textBody().contains("Sự kiện đã được cảnh báo trong: 1 giờ 2 phút 3 giây"));
		assertTrue(message.textBody().contains("ATdigital IoT"));
		assertFalse(message.subject().contains("OpenEMS"));
		assertFalse(message.textBody().contains("OpenEMS"));
		assertFalse(message.htmlBody().contains("OpenEMS"));
		assertFalse(message.textBody().contains("["));
		assertFalse(message.textBody().contains("]"));
		assertTrue(message.htmlBody().contains("ATdigital IoT"));
		assertTrue(message.htmlBody().contains("#012596"));
		assertTrue(message.htmlBody().contains("Thời gian đã cảnh báo"));
		assertTrue(message.htmlBody().contains("24/08/2026 10:30:00 +07:00"));
		assertTrue(message.htmlBody().contains("Lộc Ninh 1 &amp; 2"));
		assertTrue(message.htmlBody().contains("T&lt;36&gt;"));
		assertFalse(message.htmlBody().contains("T<36>"));
	}
}
