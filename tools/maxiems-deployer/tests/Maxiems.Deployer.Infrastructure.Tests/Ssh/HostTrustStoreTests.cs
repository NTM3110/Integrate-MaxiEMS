using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Ssh;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Ssh;

[TestClass]
[TestCategory("SshTransport")]
public sealed class HostTrustStoreTests
{
    private string temporaryDirectory = null!;
    private MutableTimeProvider timeProvider = null!;

    [TestInitialize]
    public void Initialize()
    {
        this.temporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-trust-{Guid.NewGuid():N}");
        Directory.CreateDirectory(this.temporaryDirectory);
        this.timeProvider = new(new(2026, 7, 19, 1, 0, 0, TimeSpan.Zero));
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(this.temporaryDirectory, true);

    [TestMethod]
    public async Task FirstConfirmationPersistsEndpointScopedPinAtomically()
    {
        var path = Path.Combine(this.temporaryDirectory, "trust.json");
        var store = new HostTrustStore(path, this.timeProvider);
        var endpoint = new HostEndpoint("Edge-01.EXAMPLE", 2222);
        var fingerprint = SshFingerprint.Create("ssh-ed25519", "YWJjMTIz");

        var saved = await store.ConfirmAsync(endpoint, fingerprint);
        var loaded = await new HostTrustStore(path, this.timeProvider).FindAsync(endpoint);

        Assert.AreEqual("[edge-01.example]:2222", saved.EndpointKey);
        Assert.AreEqual(saved, loaded);
        Assert.IsNull(saved.Replacement);
        Assert.AreEqual(0, Directory.GetFiles(this.temporaryDirectory, "*.tmp").Length);
    }

    [TestMethod]
    public async Task ExplicitReplacementRetainsOldToNewAuditMetadata()
    {
        var store = this.CreateStore();
        var endpoint = new HostEndpoint("edge-01", 22);
        var oldFingerprint = SshFingerprint.Create("ssh-ed25519", "b2xkLWtleQ");
        await store.ConfirmAsync(endpoint, oldFingerprint);
        this.timeProvider.Advance(TimeSpan.FromHours(1));

        var replacement = await store.ConfirmAsync(
            endpoint,
            SshFingerprint.Create("ssh-ed25519", "bmV3LWtleQ"));

        Assert.AreEqual(oldFingerprint.Sha256Value, replacement.Replacement!.PreviousFingerprint);
        Assert.AreEqual(oldFingerprint.Algorithm, replacement.Replacement.PreviousAlgorithm);
        Assert.AreEqual(replacement.LastConfirmedAt, replacement.Replacement.ReplacedAt);
        Assert.AreNotEqual(replacement.FirstSeenAt, replacement.LastConfirmedAt);
    }

    [TestMethod]
    public async Task HostAndPortPinsAreIndependent()
    {
        var store = this.CreateStore();
        await store.ConfirmAsync(new("edge-01", 22), SshFingerprint.Create("ssh-ed25519", "cG9ydDIy"));
        await store.ConfirmAsync(new("edge-01", 2222), SshFingerprint.Create("rsa-sha2-512", "cG9ydDIyMjI"));

        var first = await store.FindAsync(new("EDGE-01", 22));
        var second = await store.FindAsync(new("edge-01", 2222));

        Assert.AreNotEqual(first!.Fingerprint, second!.Fingerprint);
    }

    [TestMethod]
    public async Task MalformedStoreIsQuarantinedAndTreatedAsUntrusted()
    {
        var path = Path.Combine(this.temporaryDirectory, "trust.json");
        await File.WriteAllTextAsync(path, "{not-json");
        var store = new HostTrustStore(path, this.timeProvider);

        var result = await store.FindAsync(new("edge-01", 22));

        Assert.IsNull(result);
        Assert.IsFalse(File.Exists(path));
        Assert.AreEqual(1, Directory.GetFiles(this.temporaryDirectory, "trust.json.corrupt-*").Length);
    }

    [TestMethod]
    public async Task ConcurrentConfirmationsKeepAValidCompleteDocument()
    {
        var confirmations = Enumerable.Range(0, 20).Select(index => this.CreateStore().ConfirmAsync(
            new($"edge-{index:D2}", 22),
            SshFingerprint.Create("ssh-ed25519", Convert.ToBase64String(BitConverter.GetBytes(index)).TrimEnd('='))));

        await Task.WhenAll(confirmations);
        var store = this.CreateStore();

        for (var index = 0; index < 20; index++)
        {
            Assert.IsNotNull(await store.FindAsync(new($"edge-{index:D2}", 22)));
        }
    }

    private HostTrustStore CreateStore() => new(
        Path.Combine(this.temporaryDirectory, "trust.json"),
        this.timeProvider);

    private sealed class MutableTimeProvider(DateTimeOffset current) : TimeProvider
    {
        private DateTimeOffset current = current;

        public override DateTimeOffset GetUtcNow() => this.current;

        public void Advance(TimeSpan amount) => this.current += amount;
    }
}
