package io.openems.edge.modem.manager;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class ModemNetworkDiscoveryTest {

	@Test
	public void testDiscoversOnlyModemLikeGlobalUpInterfaces() {
		var ipOutput = "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n"
				+ "2: eth0    inet 10.0.0.7/24 scope global eth0\\       valid_lft forever preferred_lft forever\n"
				+ "3: wlan0    inet 192.168.1.20/24 scope global wlan0\\       valid_lft forever preferred_lft forever\n"
				+ "4: tailscale0    inet 100.64.0.1/32 scope global tailscale0\\       valid_lft forever preferred_lft forever\n"
				+ "5: usb0    inet 192.168.43.100/24 scope global usb0\\       valid_lft forever preferred_lft forever\n"
				+ "6: ppp0    inet 10.64.64.64 peer 10.112.112.112/32 scope global ppp0\\       valid_lft forever preferred_lft forever\n";

		var addresses = ModemNetworkDiscovery.parseGlobalAddresses(ipOutput);

		assertEquals(List.of(new ModemNetworkDiscovery.ModemIpAddress("usb0", "192.168.43.100"),
				new ModemNetworkDiscovery.ModemIpAddress("ppp0", "10.64.64.64")), addresses);
		assertEquals("192.168.43.100", ModemNetworkDiscovery.firstModemIp(ipOutput));
	}

	@Test
	public void testSortsAtPortsWithTtyUsb2First() {
		assertEquals(List.of("/dev/ttyUSB2", "/dev/ttyUSB1", "/dev/ttyACM0"),
				ModemNetworkDiscovery.sortAtPortCandidates(List.of("/dev/ttyUSB1", "/dev/ttyACM0", "/dev/ttyUSB2")));
	}
}
