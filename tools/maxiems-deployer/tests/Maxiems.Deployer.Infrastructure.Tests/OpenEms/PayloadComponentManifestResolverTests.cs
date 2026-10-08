using System.IO.Compression;
using Maxiems.Deployer.Infrastructure.OpenEms;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.OpenEms;

[TestClass]
[TestCategory("OpenEmsContract")]
public sealed class PayloadComponentManifestResolverTests
{
    [TestMethod]
    public void BundledManifestIsUsedWithoutCliManifestAndExternalEntriesAreMerged()
    {
        using var fixture = new ManifestFixture();
        fixture.WritePayload("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","id":"managed0","updateOnly":true,"properties":{"port":8085}}]}
            """);
        var external = fixture.WriteExternal("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Optional","id":"optional0","properties":{"enabled":true}}]}
            """);

        var bundled = PayloadComponentManifestResolver.Resolve(fixture.PayloadPath, null);
        var merged = PayloadComponentManifestResolver.Resolve(fixture.PayloadPath, external);

        Assert.IsNotNull(bundled);
        Assert.IsTrue(bundled.Components.Single().UpdateOnly);
        Assert.AreEqual(2, merged!.Components.Length);
    }

    [TestMethod]
    public void ExternalManifestCannotOverrideBundledManagedComponent()
    {
        using var fixture = new ManifestFixture();
        fixture.WritePayload("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","id":"managed0","updateOnly":true,"properties":{"port":8085}}]}
            """);
        var external = fixture.WriteExternal("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","id":"managed0","properties":{"port":9000}}]}
            """);

        Assert.ThrowsExactly<ComponentManifestException>(
            () => PayloadComponentManifestResolver.Resolve(fixture.PayloadPath, external));
    }

    [TestMethod]
    public void FactorySelectorOverlappingBundledExactComponentIsRejected()
    {
        using var fixture = new ManifestFixture();
        fixture.WritePayload("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","id":"managed0","updateOnly":true,"properties":{"port":8085}}]}
            """);
        var external = fixture.WriteExternal("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","properties":{"enabled":true}}]}
            """);

        Assert.ThrowsExactly<ComponentManifestException>(
            () => PayloadComponentManifestResolver.Resolve(fixture.PayloadPath, external));
    }

    [TestMethod]
    public void DuplicateFactorySelectorsAcrossBundledAndExternalAreRejected()
    {
        using var fixture = new ManifestFixture();
        fixture.WritePayload("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","properties":{"port":8085}}]}
            """);
        var external = fixture.WriteExternal("""
            {"schemaVersion":1,"components":[{"factoryPid":"Factory.Managed","properties":{"enabled":true}}]}
            """);

        Assert.ThrowsExactly<ComponentManifestException>(
            () => PayloadComponentManifestResolver.Resolve(fixture.PayloadPath, external));
    }

    [TestMethod]
    public void MissingBundledManifestIsRejectedEvenWhenNoExternalManifestWasRequested()
    {
        using var fixture = new ManifestFixture();
        fixture.WriteEmptyPayload();

        var exception = Assert.ThrowsExactly<ComponentManifestException>(
            () => PayloadComponentManifestResolver.Resolve(fixture.PayloadPath, null));

        StringAssert.Contains(exception.Message, "Build a full release");
    }

    private sealed class ManifestFixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), $"maxiems-manifest-{Guid.NewGuid():N}");

        public ManifestFixture()
        {
            Directory.CreateDirectory(this.root);
        }

        public string PayloadPath => Path.Combine(this.root, "payload.zip");

        public void WritePayload(string json)
        {
            using var archive = ZipFile.Open(this.PayloadPath, ZipArchiveMode.Create);
            var entry = archive.CreateEntry(PayloadComponentManifestResolver.BundledManifestPath);
            using var writer = new StreamWriter(entry.Open());
            writer.Write(json);
        }

        public void WriteEmptyPayload()
        {
            using var archive = ZipFile.Open(this.PayloadPath, ZipArchiveMode.Create);
            archive.CreateEntry("manifest.json");
        }

        public string WriteExternal(string json)
        {
            var path = Path.Combine(this.root, "external.json");
            File.WriteAllText(path, json);
            return path;
        }

        public void Dispose() => Directory.Delete(this.root, recursive: true);
    }
}
