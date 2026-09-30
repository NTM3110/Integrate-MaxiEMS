package io.openems.edge.controller.alarm.threshold;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.wika.api.WikaAms01Alarm;

public class ControllerAlarmThresholdImplTest {
	@Test
	public void testAlarmIsDebouncedBatchedAndNotRepeated() throws Exception {
		var componentManager = new DummyComponentManager();
		var wika = new DummyWika("wika0", "T35");
		var email = new DummyEmailService();
		componentManager.addComponent(wika);

		new ControllerTest(new ControllerAlarmThresholdImpl())
				.addReference("cm", new DummyConfigurationAdmin())
				.addReference("componentManager", componentManager)
				.addReference("emailService", email)
				.addComponent(wika)
				.activate(new MyConfig("ctrl0", "Lộc Ninh 1", 0, 0))
				.next(new TestCase().input("wika0", WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_FLAG, 1)
						.input("wika0", WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_VALUE, 85.5F))
				.next(new TestCase().input("wika0", WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_FLAG, 1))
				.next(new TestCase().input("wika0", WikaAms01Alarm.ChannelId.IN_ALARM_TEMP_FLAG, 1))
				.deactivate();

		assertEquals(1, email.messages().size());
		assertTrue(email.messages().get(0).textBody().contains("Tên trạm: T35"));
		assertTrue(email.messages().get(0).textBody().contains("Khoang: IN"));
		assertTrue(email.messages().get(0).textBody().contains("Mức cảnh báo: WARNING"));
		assertTrue(email.messages().get(0).textBody().contains("Giá trị sai lệch: 85.5 °C"));
	}
}
