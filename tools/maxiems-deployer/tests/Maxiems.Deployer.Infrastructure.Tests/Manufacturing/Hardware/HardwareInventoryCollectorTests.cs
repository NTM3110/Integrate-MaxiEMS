using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Manufacturing.Hardware;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Hardware;

[TestClass]
[TestCategory("HardwareInventory")]
public sealed class HardwareInventoryCollectorTests
{
    private static readonly string[] SortedEthInterfaces = ["eth0", "eth1"];

    [TestMethod]
    public async Task Arm64FixtureFiltersVirtualLinksAndPreservesPermanentIdentity()
    {
        var transport = FixtureTransport("aarch64", "eth1", "eth0");

        var inventory = await new HardwareInventoryCollector(transport).CollectAsync();

        Assert.AreEqual("aarch64", inventory.Cpu.Architecture);
        Assert.AreEqual("Fixture ARM Board", inventory.OperatingSystem.BoardModel);
        Assert.AreEqual(4, inventory.Cpu.LogicalCoreCount);
        CollectionAssert.AreEqual(SortedEthInterfaces, inventory.EthernetPorts.Select(port => port.LinuxName).ToArray());
        var serialPort = inventory.EthernetPorts.Single(port => port.LinuxName == "eth1");
        Assert.AreEqual("00613A7D755A", serialPort.PermanentMac!.Normalized);
        Assert.AreEqual("021122334455", serialPort.CurrentMac.Normalized);
        Assert.IsNull(serialPort.PhysicalLabel);
        Assert.AreEqual("MR00613A7D755A", PermanentDeviceIdentity.TryDerive(inventory.EthernetPorts, "eth1")!.Value);
        Assert.IsNull(PermanentDeviceIdentity.TryDerive(inventory.EthernetPorts, "missing0"));
        Assert.IsTrue(inventory.Peripherals.Any(item => item is { Bus: HardwareBus.Usb, VendorId: "2c7c" }));
        Assert.IsTrue(inventory.Peripherals.Any(item => item.Bus == HardwareBus.PciExpress));
        Assert.IsTrue(inventory.Peripherals.Any(item => item.Bus == HardwareBus.Sdio
            && item.DeviceNodes.Contains("/sys/bus/sdio/devices/mmc1:0001:1")));
        Assert.IsTrue(inventory.Peripherals.Any(item => item.Bus == HardwareBus.Uart
            && item.DeviceNodes.Contains("/dev/ttyUSB0")));
        Assert.AreEqual(EvidenceAvailability.Unavailable,
            inventory.Modules.Single(module => module.Kind == ModuleKind.Cellular4G).Evidence.Availability);
    }

    [TestMethod]
    public async Task Amd64EnpFixtureIsDeterministicWhenEnumerationIsReversed()
    {
        var first = await new HardwareInventoryCollector(FixtureTransport("x86_64", "enp2s0", "enp1s0")).CollectAsync();
        var second = await new HardwareInventoryCollector(FixtureTransport("x86_64", "enp1s0", "enp2s0")).CollectAsync();

        CollectionAssert.AreEqual(first.EthernetPorts.Select(port => port.LinuxName).ToArray(),
            second.EthernetPorts.Select(port => port.LinuxName).ToArray());
        Assert.AreEqual(StorageKind.Nvme, first.StorageDevices.Single().Kind);
        Assert.AreEqual("ubuntu", first.OperatingSystem.OperatingSystemId);
        Assert.AreEqual("Fixture x86 Board", first.OperatingSystem.BoardModel);
    }

    [TestMethod]
    public async Task MissingEthtoolNeverFallsBackToSpoofableCurrentMac()
    {
        var transport = FixtureTransport("aarch64", "eth0", "eth1");
        transport.Results[(RemoteProgram.PermanentMacProbe, "eth1")] = Result(127, string.Empty, "ethtool: not found");

        var inventory = await new HardwareInventoryCollector(transport).CollectAsync();
        var port = inventory.EthernetPorts.Single(item => item.LinuxName == "eth1");

        Assert.IsNull(port.PermanentMac);
        Assert.AreEqual(EvidenceAvailability.Unavailable, port.Evidence.Availability);
        Assert.IsNull(PermanentDeviceIdentity.TryDerive(inventory.EthernetPorts, "eth1"));
    }

    [TestMethod]
    public async Task MissingUsbAndPciToolsRemainUnavailableInsteadOfLookingAbsent()
    {
        var transport = FixtureTransport("aarch64", "eth0", "eth1");
        transport.Results[(RemoteProgram.UsbInventoryProbe, string.Empty)] = Result(127, string.Empty, "lsusb not found");
        transport.Results[(RemoteProgram.PciInventoryProbe, string.Empty)] = Result(127, string.Empty, "lspci not found");

        var inventory = await new HardwareInventoryCollector(transport).CollectAsync();

        Assert.IsTrue(inventory.Peripherals.Any(item => item.Bus == HardwareBus.Usb
            && item.Evidence.Availability == EvidenceAvailability.Unavailable));
        Assert.IsTrue(inventory.Peripherals.Any(item => item.Bus == HardwareBus.PciExpress
            && item.Evidence.Availability == EvidenceAvailability.Unavailable));
    }

    [TestMethod]
    public async Task OptionalBluetoothTimeoutIsRecordedAsUnavailableAndInspectionContinues()
    {
        var transport = new TimeoutBluetoothTransport(FixtureTransport("aarch64", "eth0", "eth1"));

        var inventory = await new HardwareInventoryCollector(transport).CollectAsync();

        var bluetooth = inventory.Modules.Single(module => module.Kind == ModuleKind.BluetoothLowEnergy);
        Assert.AreEqual(EvidenceAvailability.Unavailable, bluetooth.Evidence.Availability);
        StringAssert.Contains(bluetooth.Evidence.Summary, "timed out");
        Assert.AreEqual(2, inventory.EthernetPorts.Length);
    }

    [TestMethod]
    public async Task MalformedAndHugeProbeOutputAreRejectedExplicitly()
    {
        var malformed = FixtureTransport("aarch64", "eth0", "eth1");
        malformed.Results[(RemoteProgram.NetworkLinksProbe, string.Empty)] = Result(0, "{not-json", string.Empty);
        await Assert.ThrowsExactlyAsync<HardwareInventoryException>(() =>
            new HardwareInventoryCollector(malformed).CollectAsync());

        var huge = FixtureTransport("aarch64", "eth0", "eth1");
        huge.Results[(RemoteProgram.NetworkLinksProbe, string.Empty)] = Result(0, new string('x', 1024 * 1024 + 1), string.Empty);
        var exception = await Assert.ThrowsExactlyAsync<HardwareInventoryException>(() =>
            new HardwareInventoryCollector(huge).CollectAsync());
        StringAssert.Contains(exception.Message, "bounded output limit");

        var malformedUsb = FixtureTransport("aarch64", "eth0", "eth1");
        malformedUsb.Results[(RemoteProgram.UsbInventoryProbe, string.Empty)] = Result(0, "not lsusb data", string.Empty);
        await Assert.ThrowsExactlyAsync<HardwareInventoryException>(() =>
            new HardwareInventoryCollector(malformedUsb).CollectAsync());
    }

    private static FakeTransport FixtureTransport(string architecture, string firstInterface, string secondInterface)
    {
        var links = $$$"""
			[
			 {"ifname":"{{{firstInterface}}}","address":"02:11:22:33:44:55","link_type":"ether","flags":["UP"]},
			 {"ifname":"tailscale0","address":"02:00:00:00:00:01","link_type":"none"},
			 {"ifname":"docker0","address":"02:00:00:00:00:02","link_type":"ether","linkinfo":{"info_kind":"bridge"}},
			 {"ifname":"wlan0","address":"02:00:00:00:00:03","link_type":"ether"},
			 {"ifname":"{{{secondInterface}}}","address":"00:61:3a:7d:75:5b","link_type":"ether","flags":[]}
			]
			""";
        var results = new Dictionary<(RemoteProgram, string), RemoteCommandResult>
        {
            [(RemoteProgram.NetworkLinksProbe, string.Empty)] = Result(0, links, string.Empty),
            [(RemoteProgram.PermanentMacProbe, firstInterface)] = Result(0,
                firstInterface.EndsWith('1') ? "Permanent address: 00:61:3a:7d:75:5a" : "Permanent address: 00:61:3a:7d:75:5c", string.Empty),
            [(RemoteProgram.PermanentMacProbe, secondInterface)] = Result(0,
                secondInterface.EndsWith('1') ? "Permanent address: 00:61:3a:7d:75:5a" : "Permanent address: 00:61:3a:7d:75:5c", string.Empty),
            [(RemoteProgram.CpuInventoryProbe, string.Empty)] = Result(0,
                $"{{\"lscpu\":[{{\"field\":\"Architecture:\",\"data\":\"{architecture}\"}},{{\"field\":\"CPU(s):\",\"data\":\"4\"}},{{\"field\":\"Model name:\",\"data\":\"Fixture CPU\"}}]}}", string.Empty),
            [(RemoteProgram.MemoryProbe, string.Empty)] = Result(0, "Mem: 4294967296 1 2 3 4 5", string.Empty),
            [(RemoteProgram.BlockDeviceProbe, string.Empty)] = Result(0,
                architecture == "x86_64" ? Storage("nvme0n1", "nvme") : Storage("mmcblk0", null), string.Empty),
            [(RemoteProgram.OsReleaseProbe, string.Empty)] = Result(0, "ID=ubuntu\nVERSION_ID=\"24.04\"", string.Empty),
            [(RemoteProgram.KernelInventoryProbe, string.Empty)] = Result(0, "6.8.0 fixture", string.Empty),
            [(RemoteProgram.BoardInventoryProbe, string.Empty)] = architecture == "x86_64"
                ? Result(0, "Fixture x86 Board", string.Empty) : Result(1, string.Empty, "DMI unavailable"),
            [(RemoteProgram.DeviceTreeBoardInventoryProbe, string.Empty)] = architecture == "x86_64"
                ? Result(1, string.Empty, "device tree unavailable") : Result(0, "Fixture ARM Board", string.Empty),
            [(RemoteProgram.ImageVersionProbe, string.Empty)] = Result(0, "image-1", string.Empty),
            [(RemoteProgram.UsbInventoryProbe, string.Empty)] = Result(0, "Bus 001 Device 002: ID 2c7c:0125 Quectel", string.Empty),
            [(RemoteProgram.PciInventoryProbe, string.Empty)] = Result(0, "00:01.0 Network controller [0280]: Fixture [1234:5678]", string.Empty),
            [(RemoteProgram.ModemInventoryProbe, string.Empty)] = Result(127, string.Empty, "mmcli not found"),
            [(RemoteProgram.WirelessInventoryProbe, string.Empty)] = Result(0, "phy#0\n Interface wlan0", string.Empty),
            [(RemoteProgram.BluetoothInventoryProbe, string.Empty)] = Result(127, string.Empty, "bluetoothctl not found"),
            [(RemoteProgram.DeviceNodeInventoryProbe, string.Empty)] = Result(0, "/dev/ttyUSB0\n/dev/null", string.Empty),
            [(RemoteProgram.SdioInventoryProbe, string.Empty)] = Result(0, "/sys/bus/sdio/devices/mmc1:0001:1", string.Empty),
        };
        return new(results);
    }

    private static string Storage(string name, string? transport)
    {
        var transportJson = transport is null ? "null" : $"\"{transport}\"";
        return $"{{\"blockdevices\":[{{\"name\":\"{name}\",\"type\":\"disk\",\"size\":64000000000,\"model\":\"Fixture Disk\",\"serial\":\"DISK1\",\"tran\":{transportJson},\"mountpoints\":[\"/\"]}}]}}";
    }
    private static RemoteCommandResult Result(int exit, string output, string error) =>
        new(exit, output, error, TimeSpan.FromMilliseconds(1));

    private sealed class FakeTransport(Dictionary<(RemoteProgram, string), RemoteCommandResult> results)
        : IRemoteCommandTransport
    {
        public Dictionary<(RemoteProgram, string), RemoteCommandResult> Results { get; } = results;
        public Task<RemoteCommandResult> ExecuteAsync(RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var argument = request.Arguments.Length == 0 ? string.Empty : request.Arguments.Single().Value;
            return Task.FromResult(this.Results[(request.Program, argument)]);
        }
    }

    private sealed class TimeoutBluetoothTransport(IRemoteCommandTransport inner) : IRemoteCommandTransport
    {
        public Task<RemoteCommandResult> ExecuteAsync(RemoteCommandRequest request,
            CancellationToken cancellationToken = default) => request.Program == RemoteProgram.BluetoothInventoryProbe
            ? Task.FromCanceled<RemoteCommandResult>(new(canceled: true))
            : inner.ExecuteAsync(request, cancellationToken);
    }
}
