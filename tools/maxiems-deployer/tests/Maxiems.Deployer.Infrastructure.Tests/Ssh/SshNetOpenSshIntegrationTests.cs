using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Transport;
using Maxiems.Deployer.Infrastructure.Ssh;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Ssh;

[TestClass]
[TestCategory("SshTransport")]
[DoNotParallelize]
public sealed class SshNetOpenSshIntegrationTests
{
    [TestMethod]
    public async Task RealOpenSshCoversEncryptedKeysPinningRotationAndReconnect()
    {
        var openSshDirectory = Environment.GetEnvironmentVariable("MAXIEMS_OPENSSH_DIR");
        if (string.IsNullOrWhiteSpace(openSshDirectory))
        {
            openSshDirectory = Path.Combine(Path.GetTempPath(), "maxiems-openssh-fixture", "OpenSSH-Win64");
        }
        if (!File.Exists(Path.Combine(openSshDirectory, "sshd.exe")))
        {
            Assert.Inconclusive("Run tests/ssh/Prepare-OpenSshFixture.ps1 before the real OpenSSH integration test.");
        }
        await using var fixture = await OpenSshFixture.StartAsync(openSshDirectory);
        var endpoint = new HostEndpoint("127.0.0.1", fixture.Port);
        var trustPath = Path.Combine(Path.GetTempPath(), $"maxiems-integration-trust-{Guid.NewGuid():N}.json");
        try
        {
            var store = new HostTrustStore(trustPath);
            var coordinator = new HostTrustCoordinator(new SshNetHostKeyProbe(), store);
            var firstSeen = await coordinator.EvaluateAsync(endpoint, Environment.UserName, TimeSpan.FromSeconds(5));
            Assert.AreEqual(HostTrustStatus.FirstSeen, firstSeen.Status);
            Assert.IsFalse(fixture.Log.Contains("Accepted publickey", StringComparison.OrdinalIgnoreCase));

            await coordinator.ConfirmAsync(firstSeen);
            await ConnectUploadAndDisposeAsync(endpoint, fixture.EncryptedEd25519Key, fixture.RemoteUploadDirectory, store);
            var known = await coordinator.EvaluateAsync(endpoint, Environment.UserName, TimeSpan.FromSeconds(5));
            Assert.AreEqual(HostTrustStatus.KnownMatch, known.Status);

            var oldFingerprint = firstSeen.ObservedFingerprint;
            await fixture.RotateHostKeyAsync("rsa");
            var changed = await coordinator.EvaluateAsync(endpoint, Environment.UserName, TimeSpan.FromSeconds(5));
            Assert.AreEqual(HostTrustStatus.Changed, changed.Status);
            Assert.AreEqual(oldFingerprint, changed.PreviousFingerprint);
            Assert.AreEqual(oldFingerprint.Sha256Value, (await store.FindAsync(endpoint))!.Fingerprint);

            await coordinator.ConfirmAsync(changed);
            await ConnectUploadAndDisposeAsync(endpoint, fixture.EncryptedRsaKey, fixture.RemoteUploadDirectory, store);
            var replacement = await store.FindAsync(endpoint);
            Assert.AreEqual(oldFingerprint.Sha256Value, replacement!.Replacement!.PreviousFingerprint);
        }
        finally
        {
            File.Delete(trustPath);
        }
    }

    [TestMethod]
    public async Task ProbeHonorsCancellationAndTimeout()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsExactlyAsync<OperationCanceledException>(() => new SshNetHostKeyProbe().ProbeAsync(
            new("192.0.2.1", 65000),
            "nobody",
            TimeSpan.FromSeconds(30),
            cancellation.Token));
    }

    private static async Task ConnectUploadAndDisposeAsync(
        HostEndpoint endpoint,
        string privateKeyPath,
        string uploadDirectory,
        HostTrustStore store)
    {
        var options = new SshTransportOptions(
            endpoint,
            Environment.UserName,
            ReadOnlyMemory<char>.Empty,
            privateKeyPath,
            "fixture-passphrase".AsMemory(),
            TimeSpan.FromSeconds(5),
            TimeSpan.FromSeconds(5));
        await using var session = await SshNetTransportSession.ConnectForFixtureAsync(options, store, uploadDirectory);
        await using var upload = new MemoryStream([1, 2, 3, 4, 5, 6, 7, 8]);
        var uploadResult = await session.UploadToRandomStagingAsync(upload);
        Assert.AreEqual(8, uploadResult.Length);
        Assert.AreEqual("66840dda154e8a113c31dd0ad32f7f3a366a80e8136979d8f5a101d3d29d6f72", uploadResult.Sha256);
        var nonZero = await session.ExecuteAsync(new(
            RemoteProgram.FailureProbe,
            [],
            TimeSpan.FromSeconds(5)));
        Assert.AreNotEqual(0, nonZero.ExitCode);
        Assert.IsFalse(string.IsNullOrWhiteSpace(nonZero.StandardError));
    }
}
