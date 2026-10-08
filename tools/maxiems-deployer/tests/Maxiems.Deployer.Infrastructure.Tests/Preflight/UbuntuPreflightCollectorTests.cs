using System.Collections.Immutable;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Preflight;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Preflight;

[TestClass]
[TestCategory("UbuntuPreflight")]
public sealed class UbuntuPreflightCollectorTests
{
    [TestMethod]
    public async Task FixedReadOnlyProbeSetParsesUbuntuStateWithoutGeneralShell()
    {
        var transport = new FixtureTransport(new Dictionary<RemoteProgram, RemoteCommandResult>
        {
            [RemoteProgram.OsReleaseProbe] = Result("ID=ubuntu\nVERSION_ID=\"24.04\"\n"),
            [RemoteProgram.ArchitectureProbe] = Result("amd64\n"),
            [RemoteProgram.DiskProbe] = Result("Avail\n4294967296\n"),
            [RemoteProgram.MemoryProbe] = Result("Mem: 1073741824 1 2 3\n"),
            [RemoteProgram.TimeProbe] = Result("2026-07-19T12:00:00+00:00\n"),
            [RemoteProgram.DnsProbe] = Result("91.189.91.82 STREAM archive.ubuntu.com\n"),
            [RemoteProgram.SudoProbe] = Result(string.Empty),
            [RemoteProgram.PortProbe] = Result("LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*\n"),
            [RemoteProgram.ServiceProbe] = Result("inactive\nactive\nactive\n", 3),
            [RemoteProgram.LegacyServiceEnabledProbe] = Result("enabled\n"),
            [RemoteProgram.AccountProbe] = Result("maxiems-deploy:x:991:991::/var/lib/maxiems-deploy:/bin/bash\n"),
        });

        var snapshot = await new UbuntuPreflightCollector(transport).CollectAsync();

        Assert.AreEqual("ubuntu", snapshot.OperatingSystemId);
        Assert.AreEqual(4_294_967_296, snapshot.FreeDiskBytes);
        Assert.AreEqual(22, snapshot.Listeners.Single().Port);
        Assert.AreEqual(991, snapshot.ExistingAccounts.Single().UserId);
        Assert.IsTrue(snapshot.NginxActive);
        Assert.IsTrue(snapshot.LegacyOpenEmsActive);
        Assert.IsTrue(snapshot.LegacyOpenEmsEnabled);
        Assert.AreEqual(10, transport.Requests.Count);
        Assert.IsFalse(transport.Requests.Any(request => request.Program == RemoteProgram.DnsProbe));
        Assert.IsTrue(transport.Requests.All(request => request.Arguments.Length == 0));
    }

    private static RemoteCommandResult Result(string output, int exitCode = 0) =>
        new(exitCode, output, string.Empty, TimeSpan.FromMilliseconds(1));

    private sealed class FixtureTransport(IReadOnlyDictionary<RemoteProgram, RemoteCommandResult> results)
        : IRemoteCommandTransport
    {
        public List<RemoteCommandRequest> Requests { get; } = [];

        public Task<RemoteCommandResult> ExecuteAsync(
            RemoteCommandRequest request,
            CancellationToken cancellationToken = default)
        {
            this.Requests.Add(request);
            return Task.FromResult(results[request.Program]);
        }
    }
}
