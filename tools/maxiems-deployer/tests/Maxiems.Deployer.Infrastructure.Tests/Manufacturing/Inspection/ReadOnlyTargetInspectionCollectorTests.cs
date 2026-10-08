using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Manufacturing.Inspection;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Inspection;

[TestClass]
public sealed class ReadOnlyTargetInspectionCollectorTests
{
    [TestMethod]
    public async Task CollectExecutesOnlyReadOnlyProgramsAndFreezesState()
    {
        var transport = new FixtureTransport();
        var release = new LocalReleaseDescriptor(new("release-2"), "2.0.0", LocalReleaseSource.Imported,
            LocalReleaseStatus.ImportedVerified, new('a', 64), new('b', 64), ["24.04"],
            [SupportedArchitecture.Amd64], "1.0.0");

        var inspection = await new ReadOnlyTargetInspectionCollector(transport).CollectAsync(
            new("192.168.127.19", 22), release);

        Assert.IsTrue(inspection.SelectedReleaseCompatible);
        Assert.AreEqual("release-1", inspection.Installation.CurrentReleaseId!.Value.Value);
        Assert.IsTrue(inspection.Installation.ConfigurationPresent);
        Assert.IsTrue(inspection.Installation.RollbackAvailable);
        Assert.AreEqual(64, inspection.SnapshotSha256.Length);
        Assert.IsFalse(transport.Programs.Contains(RemoteProgram.DeployHelper));
        Assert.IsFalse(transport.Programs.Contains(RemoteProgram.NetworkHelper));
        Assert.IsTrue(transport.Programs.All(program => program.ToString().EndsWith("Probe", StringComparison.Ordinal)));
    }

    [TestMethod]
    public async Task CollectClassifiesExternalAndLegacyPortOwnership()
    {
        var transport = new FixtureTransport { LegacyActive = true, ManagedActive = false };
        var release = new LocalReleaseDescriptor(new("release-2"), "2.0.0", LocalReleaseSource.Imported,
            LocalReleaseStatus.ImportedVerified, new('a', 64), new('b', 64), ["24.04"],
            [SupportedArchitecture.Amd64], "1.0.0");

        var inspection = await new ReadOnlyTargetInspectionCollector(transport).CollectAsync(
            new("edge.local", 22), release);

        Assert.AreEqual(TargetServiceOwner.LegacyOpenEms,
            inspection.Installation.Ports.Single(port => port.Port == 8084).Owner);
        Assert.AreEqual(TargetServiceOwner.LegacyOpenEms,
            inspection.Installation.Ports.Single(port => port.Port == 8080).Owner);
        Assert.AreEqual(TargetServiceOwner.External,
            inspection.Installation.Ports.Single(port => port.Port == 80).Owner);
    }

    [TestMethod]
    public async Task InspectionServiceAlwaysClosesReadOnlySession()
    {
        var session = new FixtureSession();
        var service = new TargetInspectionService(new FixtureSessionFactory(session));
        var connection = new TargetInspectionConnection(new("edge.local", 22), "operator", "password".AsMemory(),
            string.Empty, ReadOnlyMemory<char>.Empty);

        await service.InspectAsync(connection, Release());

        Assert.IsTrue(session.Disposed);
    }

    private static LocalReleaseDescriptor Release() => new(new("release-2"), "2.0.0", LocalReleaseSource.Imported,
        LocalReleaseStatus.ImportedVerified, new('a', 64), new('b', 64), ["24.04"],
        [SupportedArchitecture.Amd64], "1.0.0");

    private class FixtureTransport : IRemoteCommandTransport
    {
        public List<RemoteProgram> Programs { get; } = [];
        public bool ManagedActive { get; init; } = true;
        public bool LegacyActive
        {
            get; init;
        }

        public Task<RemoteCommandResult> ExecuteAsync(RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            this.Programs.Add(request.Program);
            var argument = request.Arguments.Length == 0 ? string.Empty : request.Arguments[0].Value;
            return Task.FromResult(Result(request.Program, argument));
        }

        private RemoteCommandResult Result(RemoteProgram program, string argument) => program switch
        {
            RemoteProgram.OsReleaseProbe => Ok("ID=ubuntu\nVERSION_ID=\"24.04\""),
            RemoteProgram.ArchitectureProbe => Ok("amd64"),
            RemoteProgram.DiskProbe => Ok("Avail\n64000000000"),
            RemoteProgram.MemoryProbe => Ok("Mem: 4294967296 1 2 3 4 5"),
            RemoteProgram.TimeProbe => Ok(DateTimeOffset.UtcNow.ToString("O")),
            RemoteProgram.DnsProbe or RemoteProgram.SudoProbe => Ok("ok"),
            RemoteProgram.PortProbe => Ok("LISTEN 0 128 0.0.0.0:80 0.0.0.0:*\n"
                + "LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*\n"
                + "LISTEN 0 128 127.0.0.1:8084 0.0.0.0:*\n"
                + "LISTEN 0 128 0.0.0.0:8085 0.0.0.0:*"),
            RemoteProgram.ServiceProbe => Ok($"{(this.ManagedActive ? "active" : "inactive")}\ninactive\n{(this.LegacyActive ? "active" : "inactive")}"),
            RemoteProgram.LegacyServiceEnabledProbe => this.LegacyActive ? Ok("enabled") : Fail(),
            RemoteProgram.AccountProbe => Ok(string.Empty),
            RemoteProgram.NetworkLinksProbe => Ok("[{\"ifname\":\"eth0\",\"address\":\"00:61:3a:7d:75:5b\",\"link_type\":\"ether\",\"flags\":[\"UP\"]},{\"ifname\":\"eth1\",\"address\":\"00:61:3a:7d:75:5a\",\"link_type\":\"ether\",\"flags\":[]}]"),
            RemoteProgram.PermanentMacProbe => Ok(argument == "eth1" ? "Permanent address: 00:61:3a:7d:75:5a" : "Permanent address: 00:61:3a:7d:75:5b"),
            RemoteProgram.CpuInventoryProbe => Ok("{\"lscpu\":[{\"field\":\"Architecture:\",\"data\":\"x86_64\"},{\"field\":\"CPU(s):\",\"data\":\"4\"},{\"field\":\"Model name:\",\"data\":\"Fixture CPU\"}]}"),
            RemoteProgram.BlockDeviceProbe => Ok("{\"blockdevices\":[{\"name\":\"nvme0n1\",\"type\":\"disk\",\"size\":64000000000,\"model\":\"Disk\",\"serial\":\"D1\",\"tran\":\"nvme\",\"mountpoints\":[\"/\"]}]}"),
            RemoteProgram.KernelInventoryProbe => Ok("6.8.0 x86_64"),
            RemoteProgram.BoardInventoryProbe => Ok("Fixture board"),
            RemoteProgram.ImageVersionProbe => Ok("image-1"),
            RemoteProgram.UsbInventoryProbe or RemoteProgram.PciInventoryProbe
                or RemoteProgram.ModemInventoryProbe or RemoteProgram.WirelessInventoryProbe
                or RemoteProgram.BluetoothInventoryProbe => Fail(),
            RemoteProgram.DeviceNodeInventoryProbe or RemoteProgram.SdioInventoryProbe => Ok(string.Empty),
            RemoteProgram.ManagedServiceEnabledProbe => this.ManagedActive ? Ok("enabled") : Fail(),
            RemoteProgram.CurrentReleaseProbe => Ok("/opt/maxiems/releases/release-1"),
            RemoteProgram.ConfigurationPresentProbe => Ok(string.Empty),
            RemoteProgram.RollbackReleaseProbe => Ok("release-1\nrelease-0"),
            _ => Fail(),
        };

        private static RemoteCommandResult Ok(string output) => new(0, output, string.Empty, TimeSpan.Zero);
        private static RemoteCommandResult Fail() => new(1, string.Empty, "unavailable", TimeSpan.Zero);
    }

    private sealed class FixtureSession : FixtureTransport, IReadOnlyInspectionSession
    {
        public bool Disposed
        {
            get; private set;
        }
        public ValueTask DisposeAsync()
        {
            this.Disposed = true;
            return ValueTask.CompletedTask;
        }
    }

    private sealed class FixtureSessionFactory(FixtureSession session) : ITargetInspectionSessionFactory
    {
        public Task<IReadOnlyInspectionSession> ConnectAsync(TargetInspectionConnection connection,
            CancellationToken cancellationToken = default) => Task.FromResult<IReadOnlyInspectionSession>(session);
    }
}
