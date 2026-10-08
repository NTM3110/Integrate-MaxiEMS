using System.IO.Compression;
using System.Text.Json;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Storage;

[TestClass]
[TestCategory("ManufacturingStore")]
public sealed class ManufacturingBackupServiceTests : ManufacturingStoreTestBase
{
    [TestMethod]
    public async Task ConsistentBackupPreviewsAndRestoresWithoutOverwritingRecoveryCopy()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        var devices = new DeviceRepository(store);
        await devices.RegisterAsync(Device(profile.Reference, "00:61:3A:7D:75:5A", "00:11:22:33:44:55"), "fixture");
        var service = new ManufacturingBackupService(store,
            new FixedTimeProvider(Timestamp("2026-07-22T10:00:00Z")));

        var archive = await service.CreateAsync(Path.Combine(this.TemporaryDirectory, "backups"));
        var preview = await service.PreviewAsync(archive);
        Assert.IsTrue(preview.CanRestore);
        Assert.AreEqual(5, preview.Manifest.DatabaseSchemaVersion);
        Assert.IsFalse(preview.Manifest.ReportsIncluded);

        await devices.RegisterAsync(Device(profile.Reference, "00:61:3A:7D:75:5B", "00:11:22:33:44:56"), "fixture");
        Assert.AreEqual(2L, await ScalarInt64Async(store, "SELECT count(*) FROM devices"));
        var restored = await service.RestoreAsync(archive);

        Assert.AreEqual(1L, await ScalarInt64Async(store, "SELECT count(*) FROM devices"));
        Assert.IsTrue(File.Exists(restored.PreRestoreBackupPath));
    }

    [TestMethod]
    public async Task ChangedManifestHashBlocksRestoreBeforeMutation()
    {
        var (store, _) = await this.CreateProfileStoreAsync();
        var service = new ManufacturingBackupService(store,
            new FixedTimeProvider(Timestamp("2026-07-22T10:00:00Z")));
        var archivePath = await service.CreateAsync(Path.Combine(this.TemporaryDirectory, "backups"));
        using (var archive = ZipFile.Open(archivePath, ZipArchiveMode.Update))
        {
            var entry = archive.GetEntry("backup-manifest.json")!;
            ManufacturingBackupManifest manifest;
            using (var stream = entry.Open())
                manifest = JsonSerializer.Deserialize<ManufacturingBackupManifest>(stream)!;
            entry.Delete();
            var changed = archive.CreateEntry("backup-manifest.json");
            await using var output = changed.Open();
            await JsonSerializer.SerializeAsync(output, manifest with
            {
                DatabaseSha256 = new string('0', 64)
            });
        }

        var preview = await service.PreviewAsync(archivePath);
        Assert.IsFalse(preview.CanRestore);
        await Assert.ThrowsExactlyAsync<InvalidDataException>(() => service.RestoreAsync(archivePath));
        Assert.IsTrue(File.Exists(store.DatabasePath));
    }

    [TestMethod]
    public async Task BackupRequiresExplicitDestination()
    {
        var store = new SqliteManufacturingStore(this.DatabasePath);
        var service = new ManufacturingBackupService(store);
        await Assert.ThrowsExactlyAsync<ArgumentException>(() => service.CreateAsync(""));
    }

    [TestMethod]
    public async Task ProfileAndDeviceExportsArePortableAndContainNoCredentialFields()
    {
        var (store, profile) = await this.CreateProfileStoreAsync();
        await new DeviceRepository(store).RegisterAsync(
            Device(profile.Reference, "00:61:3A:7D:75:5A", "00:11:22:33:44:55"), "fixture");
        var destination = Path.Combine(this.TemporaryDirectory, "exports");
        var service = new ManufacturingExportService(store,
            new FixedTimeProvider(Timestamp("2026-07-22T10:00:00Z")));

        var profiles = await service.ExportProfilesAsync(destination);
        var data = await service.ExportDeviceHistoryAsync(destination);

        Assert.HasCount(1, profiles);
        Assert.AreEqual(1, data.DeviceCount);
        Assert.IsTrue(File.Exists(data.JsonPath));
        Assert.IsTrue(File.Exists(data.CsvPath));
        var corpus = string.Join('\n', Directory.GetFiles(destination).Select(File.ReadAllText));
        Assert.IsFalse(corpus.Contains("password", StringComparison.OrdinalIgnoreCase));
        Assert.IsFalse(corpus.Contains("passphrase", StringComparison.OrdinalIgnoreCase));
        StringAssert.Contains(corpus, "MR00613A7D755A");
    }

    private sealed class FixedTimeProvider(DateTimeOffset value) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => value;
    }
}
