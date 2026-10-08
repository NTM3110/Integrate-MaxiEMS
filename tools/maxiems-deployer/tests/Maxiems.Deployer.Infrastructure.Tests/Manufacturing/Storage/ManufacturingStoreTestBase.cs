using System.Collections.Immutable;
using Maxiems.Deployer.Core.Manufacturing;
using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Microsoft.Data.Sqlite;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Storage;

public abstract class ManufacturingStoreTestBase
{
    protected string TemporaryDirectory { get; private set; } = null!;
    protected string DatabasePath => Path.Combine(this.TemporaryDirectory, "manufacturing.db");
    protected static string FixturePath => Path.Combine(AppContext.BaseDirectory, "fixtures",
        "manufacturing-profile.example.v1.json");

    [TestInitialize]
    public void InitializeTemporaryStore()
    {
        this.TemporaryDirectory = Path.Combine(Path.GetTempPath(), $"maxiems-store-{Guid.NewGuid():N}");
        Directory.CreateDirectory(this.TemporaryDirectory);
    }

    [TestCleanup]
    public void CleanupTemporaryStore()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(this.TemporaryDirectory))
            Directory.Delete(this.TemporaryDirectory, true);
    }

    protected async Task<(SqliteManufacturingStore Store, ManufacturingProfile Profile)> CreateProfileStoreAsync()
    {
        var store = new SqliteManufacturingStore(this.DatabasePath);
        await store.InitializeAsync();
        var profile = await new ManufacturingProfileRepository(store).AddAsync(
            await File.ReadAllTextAsync(FixturePath), "engineer", Timestamp("2026-07-22T01:00:00Z"));
        return (store, profile);
    }

    protected static DeviceRecord Device(ManufacturingProfileReference profile, string serialMac, string otherMac)
    {
        var observed = Timestamp("2026-07-22T01:01:00Z");
        var serialSource = new MacAddress(serialMac);
        return new(Guid.NewGuid(), DeviceSerial.FromSerialSource(serialSource), DeviceLifecycleState.Registered, profile,
            ImmutableArray.Create(
                new DeviceEthernetPortRecord("LAN1", "eth0", new(otherMac), new(otherMac), false, observed),
                new DeviceEthernetPortRecord("LAN2", "eth1", serialSource, serialSource, true, observed)),
            null, null, null, null, observed, observed);
    }

    protected static async Task<long> ScalarInt64Async(SqliteManufacturingStore store, string sql)
    {
        await using var connection = await store.OpenConnectionAsync(CancellationToken.None);
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        return Convert.ToInt64(await command.ExecuteScalarAsync(), System.Globalization.CultureInfo.InvariantCulture);
    }

    protected static DateTimeOffset Timestamp(string value) => DateTimeOffset.Parse(value,
        System.Globalization.CultureInfo.InvariantCulture);
}
