using Maxiems.Deployer.Infrastructure.Manufacturing.Storage;
using Microsoft.Data.Sqlite;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Maxiems.Deployer.Infrastructure.Tests.Manufacturing.Storage;

[TestClass]
[TestCategory("ManufacturingStore")]
public sealed class ManufacturingStoreMigrationTests : ManufacturingStoreTestBase
{
    [TestMethod]
    public async Task CleanCreateEnablesSafetyPragmasAndContainsNoSecretColumns()
    {
        var store = new SqliteManufacturingStore(this.DatabasePath);
        var startup = await store.InitializeAsync();

        Assert.AreEqual(5, startup.SchemaVersion);
        Assert.IsNull(startup.PreMigrationBackupPath);
        Assert.AreEqual(1L, await ScalarInt64Async(store, "PRAGMA foreign_keys"));
        Assert.AreEqual(5L, await ScalarInt64Async(store, "SELECT MAX(version) FROM schema_migrations"));
        Assert.IsGreaterThanOrEqualTo(10L, await ScalarInt64Async(store,
            "SELECT count(*) FROM sqlite_master WHERE type='table'"));
        await using var connection = await store.OpenConnectionAsync(CancellationToken.None);
        var schema = await ReadSchemaAsync(connection);
        Assert.IsFalse(schema.Contains("password", StringComparison.OrdinalIgnoreCase));
        Assert.IsFalse(schema.Contains("passphrase", StringComparison.OrdinalIgnoreCase));
        Assert.IsFalse(schema.Contains("private_key", StringComparison.OrdinalIgnoreCase));
    }

    [TestMethod]
    public async Task ExistingUnmigratedDatabaseGetsAtomicBackupAndRestartIsIdempotent()
    {
        await using (var empty = new SqliteConnection($"Data Source={this.DatabasePath};Pooling=False"))
        {
            await empty.OpenAsync();
        }
        var store = new SqliteManufacturingStore(this.DatabasePath,
            new FixedTimeProvider(Timestamp("2026-07-22T02:03:04Z")));
        var first = await store.InitializeAsync();
        var second = await store.InitializeAsync();

        Assert.IsNotNull(first.PreMigrationBackupPath);
        Assert.IsTrue(File.Exists(first.PreMigrationBackupPath));
        Assert.IsNull(second.PreMigrationBackupPath);
        Assert.AreEqual(5L, await ScalarInt64Async(store, "SELECT count(*) FROM schema_migrations"));
    }

    [TestMethod]
    public async Task FailedMigrationRollsBackEveryStatement()
    {
        await using var connection = new SqliteConnection($"Data Source={this.DatabasePath};Pooling=False");
        await connection.OpenAsync();
        var migrations = new[] {
            new ManufacturingMigration(1, "good", "CREATE TABLE should_rollback(id INTEGER PRIMARY KEY);"),
            new ManufacturingMigration(2, "bad", "THIS IS NOT SQL;"),
        };

        await Assert.ThrowsExactlyAsync<SqliteException>(() =>
            ManufacturingMigrationRunner.ApplyAsync(connection, migrations, CancellationToken.None));
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT count(*) FROM sqlite_master WHERE name IN ('should_rollback','schema_migrations')";
        Assert.AreEqual(0L, Convert.ToInt64(await command.ExecuteScalarAsync(),
            System.Globalization.CultureInfo.InvariantCulture));
    }

    [TestMethod]
    public async Task CorruptDatabaseIsQuarantinedAndNeverSilentlyReplaced()
    {
        await File.WriteAllTextAsync(this.DatabasePath, "definitely not sqlite");
        var store = new SqliteManufacturingStore(this.DatabasePath,
            new FixedTimeProvider(Timestamp("2026-07-22T02:03:04Z")));

        var exception = await Assert.ThrowsExactlyAsync<ManufacturingStoreCorruptException>(
            () => store.InitializeAsync());

        Assert.IsFalse(File.Exists(this.DatabasePath));
        Assert.IsTrue(File.Exists(exception.QuarantinePath));
    }

    private static async Task<string> ReadSchemaAsync(SqliteConnection connection)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT group_concat(sql, ' ') FROM sqlite_master WHERE sql IS NOT NULL";
        return (string)(await command.ExecuteScalarAsync())!;
    }

    private sealed class FixedTimeProvider(DateTimeOffset value) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => value;
    }
}
