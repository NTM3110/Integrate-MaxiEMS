using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Ssh;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Ssh;

[TestClass]
[TestCategory("SshTransport")]
public sealed class HostTrustCoordinatorTests
{
    private string temporaryDirectory = null!;

    [TestInitialize]
    public void Initialize()
    {
        this.temporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-coordinator-{Guid.NewGuid():N}");
        Directory.CreateDirectory(this.temporaryDirectory);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(this.temporaryDirectory, true);

    [TestMethod]
    public async Task FirstSeenRequiresDecisionWithoutCredentialOrCommandActivity()
    {
        var observed = SshFingerprint.Create("ssh-ed25519", "Zmlyc3Qtc2Vlbg");
        var probe = new RecordingProbe(observed);
        var store = this.CreateStore();
        var coordinator = new HostTrustCoordinator(probe, store);

        var decision = await coordinator.EvaluateAsync(new("edge-01", 22), "admin", TimeSpan.FromSeconds(5));

        Assert.AreEqual(HostTrustStatus.FirstSeen, decision.Status);
        Assert.AreEqual(1, probe.ProbeCount);
        Assert.AreEqual(0, probe.CredentialOfferCount);
        Assert.AreEqual(0, probe.RemoteCommandCount);
        Assert.IsNull(await store.FindAsync(decision.Endpoint));
    }

    [TestMethod]
    public async Task ConfirmationThenFreshProbeProducesKnownMatch()
    {
        var observed = SshFingerprint.Create("ssh-ed25519", "a25vd24ta2V5");
        var coordinator = new HostTrustCoordinator(new RecordingProbe(observed), this.CreateStore());
        var first = await coordinator.EvaluateAsync(new("edge-01", 22), "admin", TimeSpan.FromSeconds(5));
        await coordinator.ConfirmAsync(first);

        var reconnectDecision = await coordinator.EvaluateAsync(
            new("edge-01", 22), "maxiems-deploy", TimeSpan.FromSeconds(5));

        Assert.AreEqual(HostTrustStatus.KnownMatch, reconnectDecision.Status);
    }

    [TestMethod]
    public async Task ChangedKeyRejectionPreservesOldPinAndPerformsNoActivity()
    {
        var oldFingerprint = SshFingerprint.Create("ssh-ed25519", "b2xkLWtleQ");
        var changedFingerprint = SshFingerprint.Create("ssh-ed25519", "Y2hhbmdlZC1rZXk");
        var store = this.CreateStore();
        await store.ConfirmAsync(new("edge-01", 22), oldFingerprint);
        var probe = new RecordingProbe(changedFingerprint);
        var coordinator = new HostTrustCoordinator(probe, store);

        var decision = await coordinator.EvaluateAsync(new("edge-01", 22), "admin", TimeSpan.FromSeconds(5));
        var stillTrusted = await store.FindAsync(new("edge-01", 22));

        Assert.AreEqual(HostTrustStatus.Changed, decision.Status);
        Assert.AreEqual(oldFingerprint.Sha256Value, stillTrusted!.Fingerprint);
        Assert.AreEqual(0, probe.CredentialOfferCount);
        Assert.AreEqual(0, probe.RemoteCommandCount);
    }

    [TestMethod]
    public async Task SuppliedOutOfBandFingerprintIsComparedBeforeStoreTrust()
    {
        var supplied = SshFingerprint.Create("rsa-sha2-512", "c3VwcGxpZWQ");
        var observed = SshFingerprint.Create("rsa-sha2-512", "b2JzZXJ2ZWQ");
        var coordinator = new HostTrustCoordinator(new RecordingProbe(observed), this.CreateStore());

        var decision = await coordinator.EvaluateAsync(
            new("edge-01", 22), "admin", TimeSpan.FromSeconds(5), supplied);

        Assert.AreEqual(HostTrustStatus.Changed, decision.Status);
        Assert.AreEqual(supplied, decision.PreviousFingerprint);
    }

    [TestMethod]
    public async Task MatchingSuppliedFingerprintCreatesPinWithoutPromptReplacement()
    {
        var supplied = SshFingerprint.Create("ssh-ed25519", "bWF0Y2hpbmc");
        var store = this.CreateStore();
        var coordinator = new HostTrustCoordinator(new RecordingProbe(supplied), store);

        var decision = await coordinator.EvaluateAsync(
            new("edge-01", 22), "admin", TimeSpan.FromSeconds(5), supplied);

        Assert.AreEqual(HostTrustStatus.KnownMatch, decision.Status);
        Assert.AreEqual(supplied.Sha256Value, (await store.FindAsync(new("edge-01", 22)))!.Fingerprint);
    }

    private HostTrustStore CreateStore() => new(Path.Combine(this.temporaryDirectory, "trust.json"));

    private sealed class RecordingProbe(SshHostFingerprint observed) : IHostKeyProbe
    {
        public int ProbeCount
        {
            get; private set;
        }

        public int CredentialOfferCount
        {
            get; private set;
        }

        public int RemoteCommandCount
        {
            get; private set;
        }

        public Task<SshHostFingerprint> ProbeAsync(
            HostEndpoint endpoint,
            string username,
            TimeSpan timeout,
            CancellationToken cancellationToken = default)
        {
            this.ProbeCount++;
            return Task.FromResult(observed);
        }
    }
}
