package io.openems.edge.network.manager.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.openems.edge.network.manager.Mode;
import io.openems.edge.network.manager.operation.NetworkInterfaceConfig;

public class NmcliNetworkAdapterTest {

	@Test
	public void appliesStaticConfigurationWithArgumentSafeCommands() throws Exception {
		var runner = new RecordingCommandRunner(List.of("uuid-eth0", "openems-eth0", "eth0"));
		var adapter = new NmcliNetworkAdapter(runner);
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24,
				"192.168.10.1", List.of("8.8.8.8", "1.1.1.1"));

		var profile = adapter.ensureProfile("eth0");
		adapter.apply(profile, config);
		adapter.activate(profile, "eth0");

		assertEquals("uuid-eth0", profile.uuid());
		assertEquals(List.of("sudo", "nmcli", "connection", "modify", "uuid", "uuid-eth0", "ipv4.method",
				"manual", "ipv4.addresses", "192.168.10.20/24", "ipv4.gateway", "192.168.10.1",
				"ipv4.dns", "8.8.8.8,1.1.1.1", "connection.autoconnect", "yes"), runner.commands.get(3));
		assertEquals(List.of("sudo", "nmcli", "connection", "up", "uuid", "uuid-eth0", "ifname", "eth0"),
				runner.commands.get(4));
		assertFalse(runner.commands.stream().flatMap(List::stream).anyMatch("bash"::equals));
	}

	@Test
	public void createsDeterministicProfileWhenInactiveProfileDoesNotExist() throws Exception {
		var runner = new RecordingCommandRunner(List.of("", "", "created-uuid", "openems-eth1", "eth1"));
		var adapter = new NmcliNetworkAdapter(runner);

		var profile = adapter.ensureProfile("eth1");

		assertEquals("created-uuid", profile.uuid());
		assertEquals(List.of("sudo", "nmcli", "connection", "add", "type", "ethernet", "ifname", "eth1",
				"con-name", "openems-eth1"), runner.commands.get(1));
		assertEquals(List.of("nmcli", "-t", "-f", "UUID", "connection",
				"show"), runner.commands.get(2));
		assertEquals(List.of("nmcli", "-g", "connection.interface-name", "connection", "show", "uuid",
				"created-uuid"), runner.commands.get(4));
	}

	@Test
	public void findsInactiveProfileWithNetworkManager146CompatibleFields() throws Exception {
		var runner = new RecordingCommandRunner(
				List.of("uuid-eth1\nuuid-eth0", "openems-eth1", "openems-eth0", "eth0"));
		var adapter = new NmcliNetworkAdapter(runner);

		var profile = adapter.ensureProfile("eth0");

		assertEquals(new NetworkProfile("uuid-eth0", "eth0"), profile);
		assertEquals(List.of("nmcli", "-t", "-f", "UUID", "connection", "show"), runner.commands.get(0));
		assertEquals(List.of("nmcli", "-g", "connection.id", "connection", "show", "uuid", "uuid-eth1"),
				runner.commands.get(1));
		assertEquals(List.of("nmcli", "-g", "connection.id", "connection", "show", "uuid", "uuid-eth0"),
				runner.commands.get(2));
		assertEquals(List.of("nmcli", "-g", "connection.interface-name", "connection", "show", "uuid",
				"uuid-eth0"), runner.commands.get(3));
	}

	@Test
	public void ignoresExternallyManagedProfileForTheSameInterface() throws Exception {
		var runner = new RecordingCommandRunner(
				List.of("uuid-netplan", "netplan-eth0", "", "created-uuid", "openems-eth0", "eth0"));
		var adapter = new NmcliNetworkAdapter(runner);

		var profile = adapter.ensureProfile("eth0");

		assertEquals(new NetworkProfile("created-uuid", "eth0"), profile);
		assertEquals(List.of("sudo", "nmcli", "connection", "add", "type", "ethernet", "ifname", "eth0",
				"con-name", "openems-eth0"), runner.commands.get(2));
	}

	@Test
	public void appliesDhcpWithOptionalDnsOverride() throws Exception {
		var runner = new RecordingCommandRunner(List.of());
		var adapter = new NmcliNetworkAdapter(runner);
		var profile = new NetworkProfile("uuid-eth0", "eth0");
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of("8.8.8.8"));

		adapter.apply(profile, config);

		assertEquals(List.of("sudo", "nmcli", "connection", "modify", "uuid", "uuid-eth0", "ipv4.method",
				"auto", "ipv4.addresses", "", "ipv4.gateway", "", "ipv4.dns", "8.8.8.8",
				"ipv4.ignore-auto-dns", "yes", "connection.autoconnect", "yes"), runner.commands.get(0));
	}

	@Test
	public void verificationDistinguishesProfileFromPhysicalCarrier() throws Exception {
		var runner = new RecordingCommandRunner(List.of("eth0\nmanual\n192.168.10.20/24\n192.168.10.1\n8.8.8.8\nyes",
				"100 (connected)\nuuid-eth0\noff"));
		var adapter = new NmcliNetworkAdapter(runner);
		var profile = new NetworkProfile("uuid-eth0", "eth0");
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24,
				"192.168.10.1", List.of("8.8.8.8"));

		var verification = adapter.verify(profile, config);

		assertEquals(true, verification.profileVerified());
		assertEquals(false, verification.linkAvailable());
		assertEquals(false, verification.runtimeVerified());
		assertEquals(List.of("nmcli", "-g", "GENERAL.STATE,GENERAL.CON-UUID,WIRED-PROPERTIES.CARRIER", "device",
				"show", "eth0"), runner.commands.get(1));
	}

	@Test
	public void verificationRejectsMismatchedGatewayAndDns() throws Exception {
		var runner = new RecordingCommandRunner(List.of("eth0\nmanual\n192.168.10.20/24\n192.168.10.254\n1.1.1.1\nyes",
				"100 (connected)\nuuid-eth0\non"));
		var adapter = new NmcliNetworkAdapter(runner);
		var profile = new NetworkProfile("uuid-eth0", "eth0");
		var config = new NetworkInterfaceConfig("eth0", Mode.STATIC, "192.168.10.20", 24,
				"192.168.10.1", List.of("8.8.8.8"));

		var verification = adapter.verify(profile, config);

		assertEquals(false, verification.profileVerified());
	}

	@Test
	public void verificationChecksDhcpDnsOverride() throws Exception {
		var runner = new RecordingCommandRunner(List.of("eth0\nauto\n\n\n8.8.8.8\nyes",
				"100 (connected)\nuuid-eth0\non"));
		var adapter = new NmcliNetworkAdapter(runner);
		var profile = new NetworkProfile("uuid-eth0", "eth0");
		var config = new NetworkInterfaceConfig("eth0", Mode.DHCP, null, null, null, List.of("8.8.8.8"));

		var verification = adapter.verify(profile, config);

		assertEquals(true, verification.profileVerified());
	}

	@Test
	public void observesDeviceStateFromNetworkManager() throws Exception {
		var runner = new RecordingCommandRunner(List.of("100 (connected)", "on", "192.168.1.20/24",
				"192.168.1.1", "192.168.1.1\n8.8.8.8", "openems-eth0"));
		var adapter = new NmcliNetworkAdapter(runner);

		var observed = adapter.observe("eth0");

		assertEquals("ACTIVATED", observed.deviceState());
		assertEquals(true, observed.carrier());
		assertEquals(List.of("192.168.1.20/24"), observed.addresses());
		assertEquals("192.168.1.1", observed.gateway());
		assertEquals(List.of("192.168.1.1", "8.8.8.8"), observed.dns());
		assertEquals("openems-eth0", observed.activeProfile());
	}

	private static final class RecordingCommandRunner implements CommandRunner {
		private final List<String> outputs;
		private final List<List<String>> commands = new ArrayList<>();
		private int outputIndex;

		private RecordingCommandRunner(List<String> outputs) {
			this.outputs = outputs;
		}

		@Override
		public String execute(List<String> command) {
			this.commands.add(List.copyOf(command));
			return this.outputIndex < this.outputs.size() ? this.outputs.get(this.outputIndex++) : "";
		}
	}
}
