using System.IO.Compression;
using System.Text;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing.Releases;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Maxiems.Deployer.Infrastructure.Payload;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Releases;

[TestClass]
[TestCategory("ReleaseCatalog")]
public sealed class ReleasePackageCatalogTests
{
    private string temporaryDirectory = null!;
    private string cacheDirectory = null!;
    private SqliteManufacturingStore store = null!;
    private static readonly ReleaseCompatibilityContext Compatible = new(new Version(1, 0, 0), "24.04",
        SupportedArchitecture.Arm64);

    [TestInitialize]
    public async Task InitializeAsync()
    {
        this.temporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-release-{Guid.NewGuid():N}");
        this.cacheDirectory = Path.Combine(this.temporaryDirectory, "cache");
        Directory.CreateDirectory(this.temporaryDirectory);
        this.store = new(Path.Combine(this.temporaryDirectory, "manufacturing.db"));
        await this.store.InitializeAsync();
    }

    [TestCleanup]
    public void Cleanup()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        if (Directory.Exists(this.temporaryDirectory))
            Directory.Delete(this.temporaryDirectory, true);
    }

    [TestMethod]
    public async Task ValidPackageImportsAndMaterializesVerifiedFrozenRelease()
    {
        var package = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory);
        var importer = new ReleasePackageImporter(this.store, this.cacheDirectory);

        var selection = await importer.ImportAsync(package, Compatible);
        var persisted = await new LocalReleaseCatalog(this.store).FindAsync(selection.Descriptor.ReleaseId);
        var payload = await new ReleasePackageMaterializer().MaterializeAsync(selection);
        try
        {
            var verification = PayloadVerifier.VerifyArchive(payload);
            Assert.IsTrue(verification.IsValid);
            Assert.AreEqual(ReleasePackageTestSupport.ReleaseId, verification.ReleaseId);
            Assert.AreEqual(LocalReleaseStatus.ImportedVerified, selection.Descriptor.Status);
            Assert.IsNotNull(persisted);
            Assert.AreEqual(selection.PackagePath, persisted.PackagePath);
            Assert.AreEqual(selection.Descriptor.ReleaseId, persisted.Descriptor.ReleaseId);
            Assert.AreEqual(selection.Descriptor.ArtifactSha256, persisted.Descriptor.ArtifactSha256);
            CollectionAssert.AreEqual(selection.Descriptor.SupportedUbuntuVersions.ToArray(),
                persisted.Descriptor.SupportedUbuntuVersions.ToArray());
        }
        finally
        {
            File.Delete(payload);
        }
    }

    [TestMethod]
    public async Task ExactDuplicateArtifactIsRejectedWithoutExtraCacheFile()
    {
        var package = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory);
        var importer = new ReleasePackageImporter(this.store, this.cacheDirectory);
        await importer.ImportAsync(package, Compatible);
        var count = Directory.EnumerateFiles(this.cacheDirectory, "*.maxiems-release").Count();

        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() => importer.ImportAsync(package, Compatible));

        Assert.AreEqual(count, Directory.EnumerateFiles(this.cacheDirectory, "*.maxiems-release").Count());
    }

    [TestMethod]
    public async Task MissingOrTamperedCacheIsRejectedOnEveryUse()
    {
        var importer = new ReleasePackageImporter(this.store, this.cacheDirectory);
        var firstPackage = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory, "first.maxiems-release");
        var missing = await importer.ImportAsync(firstPackage, Compatible);
        File.Delete(missing.PackagePath);
        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() =>
            new ReleasePackageMaterializer().MaterializeAsync(missing));

        var secondPackage = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory, "second.maxiems-release",
            releaseId: "20260722080001-123456789abd", payloadContent: "second payload");
        var tampered = await importer.ImportAsync(secondPackage, Compatible);
        await File.AppendAllTextAsync(tampered.PackagePath, "tampered", Encoding.UTF8);
        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() =>
            new ReleasePackageMaterializer().MaterializeAsync(tampered));
    }

    [TestMethod]
    public async Task WrongInnerHashAndUnsafeOuterEntryAreRejectedAtomically()
    {
        var wrongHash = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory, "wrong.maxiems-release",
            metadata => metadata["payloadSha256"] = new string('0', 64));
        var traversal = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory, "traversal.maxiems-release",
            mutatePackage: archive => WriteEntry(archive, "../escape", "escape"));
        var symlink = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory, "symlink.maxiems-release",
            mutatePackage: archive => archive.GetEntry("release.json")!.ExternalAttributes =
                unchecked((int)(0xA000u << 16)));
        var importer = new ReleasePackageImporter(this.store, this.cacheDirectory);

        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() => importer.ImportAsync(wrongHash, Compatible));
        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() => importer.ImportAsync(traversal, Compatible));
        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() => importer.ImportAsync(symlink, Compatible));

        Assert.IsFalse(Directory.EnumerateFiles(this.cacheDirectory).Any());
    }

    [TestMethod]
    public async Task FrozenSelectionDoesNotChangeWhenAnotherReleaseIsImported()
    {
        var importer = new ReleasePackageImporter(this.store, this.cacheDirectory);
        var first = await importer.ImportAsync(ReleasePackageTestSupport.CreatePackage(
            this.temporaryDirectory, "frozen-first.maxiems-release"), Compatible);
        await importer.ImportAsync(ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory,
            "frozen-second.maxiems-release", releaseId: "20260722080001-123456789abd",
            payloadContent: "different payload"), Compatible);

        var materialized = await new ReleasePackageMaterializer().MaterializeAsync(first);
        try
        {
            Assert.AreEqual(first.Descriptor.ReleaseId.Value, PayloadVerifier.VerifyArchive(materialized).ReleaseId);
        }
        finally
        {
            File.Delete(materialized);
        }
    }

    [TestMethod]
    public async Task SizeAndCompatibilityPoliciesRejectBeforeCatalogMutation()
    {
        var package = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory);
        var tinyImporter = new ReleasePackageImporter(this.store, this.cacheDirectory, new(10, 10, 10));
        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() => tinyImporter.ImportAsync(package, Compatible));

        await AssertCompatibilityRejectedAsync(metadata => metadata["schemaVersion"] = 2, Compatible);
        await AssertCompatibilityRejectedAsync(metadata => metadata["minimumDeployerVersion"] = "99.0.0", Compatible);
        await AssertCompatibilityRejectedAsync(metadata => metadata["supportedUbuntuVersions"] =
            new System.Text.Json.Nodes.JsonArray("22.04"), Compatible);
        await AssertCompatibilityRejectedAsync(metadata => metadata["supportedArchitectures"] =
            new System.Text.Json.Nodes.JsonArray("amd64"), Compatible);

        Assert.AreEqual(0L, await CountReleasesAsync(this.store));
    }

    [TestMethod]
    public void ReleaseSchemaIsValidVersionOneDocument()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "schemas", "maxiems-release.v1.schema.json");
        using var document = System.Text.Json.JsonDocument.Parse(File.ReadAllText(path));
        Assert.AreEqual(1, document.RootElement.GetProperty("properties").GetProperty("schemaVersion")
            .GetProperty("const").GetInt32());
    }

    private async Task AssertCompatibilityRejectedAsync(
        Action<System.Text.Json.Nodes.JsonObject> mutation, ReleaseCompatibilityContext context)
    {
        var path = ReleasePackageTestSupport.CreatePackage(this.temporaryDirectory,
            $"invalid-{Guid.NewGuid():N}.maxiems-release", mutation);
        await Assert.ThrowsExactlyAsync<ReleasePackageException>(() =>
            new ReleasePackageImporter(this.store, this.cacheDirectory).ImportAsync(path, context));
    }

    private static void WriteEntry(ZipArchive archive, string path, string value)
    {
        var entry = archive.CreateEntry(path);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(value);
    }

    private static async Task<long> CountReleasesAsync(SqliteManufacturingStore store)
    {
        await using var connection = await store.OpenConnectionAsync(CancellationToken.None);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT count(*) FROM local_releases";
        return Convert.ToInt64(await command.ExecuteScalarAsync(), System.Globalization.CultureInfo.InvariantCulture);
    }
}
