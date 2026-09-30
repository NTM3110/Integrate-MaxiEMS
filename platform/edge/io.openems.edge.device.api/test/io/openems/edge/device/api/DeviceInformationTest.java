package io.openems.edge.device.api;

import static io.openems.edge.common.test.TestUtils.activateNextProcessImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;

public class DeviceInformationTest {

	@Test
	public void testChannelContract() {
		for (var channelId : DeviceInformation.ChannelId.values()) {
			if (channelId == DeviceInformation.ChannelId.DEVICE_UPTIME) {
				assertEquals(OpenemsType.LONG, channelId.doc().getType());
				assertEquals(Unit.CUMULATED_SECONDS, channelId.doc().getUnit());
			} else {
				assertEquals(OpenemsType.STRING, channelId.doc().getType());
			}
			assertEquals(PersistencePriority.MEDIUM, channelId.doc().getPersistencePriority());
		}
	}

	@Test
	public void testAccessorsKeepUnknownValuesNull() {
		var sut = new DummyDeviceInformation("device0");

		assertNull(sut.getSerialNumber().get());
		assertNull(sut.getFirmwareVersion().get());
		assertNull(sut.getDeviceUptime().get());

		sut._setManufacturer("MAXiCom");
		sut._setModel("INV-100");
		sut._setSerialNumber("SN-123");
		sut._setFirmwareVersion("1.2.3");
		sut._setHardwareVersion("2.0");
		sut._setDeviceName("Roof Inverter");
		sut._setDeviceUptime(150390L);
		activateNextProcessImage(sut);

		assertEquals("MAXiCom", sut.getManufacturer().get());
		assertEquals("INV-100", sut.getModel().get());
		assertEquals("SN-123", sut.getSerialNumber().get());
		assertEquals("1.2.3", sut.getFirmwareVersion().get());
		assertEquals("2.0", sut.getHardwareVersion().get());
		assertEquals("Roof Inverter", sut.getDeviceName().get());
		assertEquals(Long.valueOf(150390L), sut.getDeviceUptime().get());
	}

	private static final class DummyDeviceInformation extends AbstractDummyOpenemsComponent<DummyDeviceInformation>
			implements DeviceInformation {

		private DummyDeviceInformation(String id) {
			super(id, DeviceInformation.ChannelId.values());
		}

		@Override
		protected DummyDeviceInformation self() {
			return this;
		}
	}
}
