using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed class SqliteManufacturingStore
{
    private readonly TimeProvider timeProvider;

    public SqliteManufacturingStore(string databasePath, TimeProvider? timeProvider = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        this.DatabasePath = Path.GetFullPath(databasePath);
        this.timeProvider = timeProvider ?? TimeProvider.System;
    }

    public string DatabasePath
    {
        get;
    }

    public async Task<ManufacturingStoreStartup> InitializeAsync(CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(this.DatabasePath)!);
        var existed = File.Exists(this.DatabasePath);
        SqliteConnection? connection = null;
        try
        {
            connection = await this.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
            if (existed)
                await EnsureIntegrityAsync(connection, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (existed && exception is SqliteException or InvalidDataException)
        {
            if (connection is not null)
                await connection.DisposeAsync().ConfigureAwait(false);
            connection = null;
            var quarantine = this.Quarantine();
            throw new ManufacturingStoreCorruptException(
                "The manufacturing database failed its integrity check and was quarantined. Recovery is required.",
                quarantine, exception);
        }
        await using (connection)
        {
            var needsMigration = await NeedsMigrationAsync(connection, cancellationToken).ConfigureAwait(false);
            var backup = existed && needsMigration
                ? await this.CreateBackupAsync(connection, cancellationToken).ConfigureAwait(false)
                : null;
            await ManufacturingMigrationRunner.ApplyAsync(connection, ManufacturingMigrations.All, cancellationToken)
                .ConfigureAwait(false);
            await ExecutePragmaAsync(connection, "PRAGMA journal_mode=WAL;", cancellationToken).ConfigureAwait(false);
            return new(this.DatabasePath, backup, ManufacturingMigrations.All.Max(item => item.Version));
        }
    }

    internal async Task<SqliteConnection> OpenConnectionAsync(CancellationToken cancellationToken)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = this.DatabasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
            DefaultTimeout = 5,
            Pooling = false,
        }.ToString());
        await connection.OpenAsync(cancellationToken).ConfigureAwait(false);
        await ExecutePragmaAsync(connection, "PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;", cancellationToken)
            .ConfigureAwait(false);
        return connection;
    }

    private static async Task EnsureIntegrityAsync(SqliteConnection connection, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA integrity_check;";
        var result = Convert.ToString(await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false),
            System.Globalization.CultureInfo.InvariantCulture);
        if (!string.Equals(result, "ok", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException($"SQLite integrity_check returned '{result}'.");
        }
    }

    private static async Task<bool> NeedsMigrationAsync(SqliteConnection connection, CancellationToken cancellationToken)
    {
        await using var table = connection.CreateCommand();
        table.CommandText = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='schema_migrations'";
        if (Convert.ToInt64(await table.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false),
            System.Globalization.CultureInfo.InvariantCulture) == 0)
            return true;
        await using var version = connection.CreateCommand();
        version.CommandText = "SELECT COALESCE(MAX(version), 0) FROM schema_migrations";
        var current = Convert.ToInt32(await version.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false),
            System.Globalization.CultureInfo.InvariantCulture);
        return current < ManufacturingMigrations.All.Max(item => item.Version);
    }

    private async Task<string> CreateBackupAsync(SqliteConnection source, CancellationToken cancellationToken)
    {
        var timestamp = this.timeProvider.GetUtcNow().ToString("yyyyMMddHHmmssfff",
            System.Globalization.CultureInfo.InvariantCulture);
        var finalPath = $"{this.DatabasePath}.pre-migration-{timestamp}.bak";
        var temporaryPath = $"{finalPath}.{Guid.NewGuid():N}.tmp";
        await using (var destination = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = temporaryPath,
            Pooling = false,
        }.ToString()))
        {
            await destination.OpenAsync(cancellationToken).ConfigureAwait(false);
            source.BackupDatabase(destination);
        }
        File.Move(temporaryPath, finalPath, false);
        return finalPath;
    }

    private string Quarantine()
    {
        var quarantine = $"{this.DatabasePath}.corrupt-{this.timeProvider.GetUtcNow():yyyyMMddHHmmssfff}";
        File.Move(this.DatabasePath, quarantine, false);
        foreach (var suffix in new[] { "-wal", "-shm" })
        {
            if (File.Exists(this.DatabasePath + suffix))
                File.Move(this.DatabasePath + suffix, quarantine + suffix, false);
        }
        return quarantine;
    }

    private static async Task ExecutePragmaAsync(
        SqliteConnection connection, string sql, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }
}
