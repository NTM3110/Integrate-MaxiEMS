using System.Text.Json;
using Maxiems.Deployer.Core.Contracts;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Storage;

[TestClass]
[TestCategory("ManufacturingStore")]
public sealed class LegacyHistoryImporterTests : ManufacturingStoreTestBase
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    [TestMethod]
    public async Task FormatOneHistoryImportsOnceWithoutInventingDeviceData()
    {
        var store = new SqliteManufacturingStore(this.DatabasePath);
        await store.InitializeAsync();
        var historyPath = Path.Combine(this.TemporaryDirectory, "history.json");
        await WriteHistoryAsync(historyPath);
        var importer = new LegacyHistoryImporter(store,
            new FixedTimeProvider(Timestamp("2026-07-22T03:04:05Z")));

        var first = await importer.ImportAsync(historyPath);
        var second = await importer.ImportAsync(historyPath);

        Assert.IsTrue(first.SourceFound);
        Assert.IsFalse(first.AlreadyImported);
        Assert.AreEqual(1, first.ImportedRunCount);
        Assert.IsTrue(File.Exists(first.SourceBackupPath));
        Assert.IsTrue(second.AlreadyImported);
        Assert.AreEqual(0, second.ImportedRunCount);
        Assert.AreEqual(1L, await ScalarInt64Async(store, "SELECT count(*) FROM deployment_runs"));
        Assert.AreEqual(1L, await ScalarInt64Async(store, """
			SELECT count(*) FROM deployment_runs
			WHERE action='LegacyUnassigned' AND legacy_unassigned=1 AND device_id IS NULL AND inspection_id IS NULL
			"""));
    }

    [TestMethod]
    public async Task MissingHistoryIsANoOp()
    {
        var store = new SqliteManufacturingStore(this.DatabasePath);
        await store.InitializeAsync();

        var result = await new LegacyHistoryImporter(store).ImportAsync(
            Path.Combine(this.TemporaryDirectory, "missing.json"));

        Assert.IsFalse(result.SourceFound);
        Assert.AreEqual(0L, await ScalarInt64Async(store, "SELECT count(*) FROM deployment_runs"));
    }

    private static async Task WriteHistoryAsync(string path)
    {
        var started = Timestamp("2026-07-21T01:00:00Z");
        var entry = new DeploymentHistorySummary(Guid.NewGuid(), "100.102.60.39:22",
            new ReleaseId("20260721010000-abcdef123456"), DeploymentFinalState.Succeeded,
            started, started.AddMinutes(2), TimeSpan.FromMinutes(2),
            new ReportReference("missing-result.json", "missing-result.html"));
        await File.WriteAllTextAsync(path, JsonSerializer.Serialize(new
        {
            formatVersion = 1,
            entries = new[] { entry }
        },
            JsonOptions));
    }

    private sealed class FixedTimeProvider(DateTimeOffset value) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => value;
    }
}
