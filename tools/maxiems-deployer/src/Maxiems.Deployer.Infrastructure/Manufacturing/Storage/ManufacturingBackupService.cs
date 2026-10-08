using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Maxiems.Deployer.Infrastructure.Manufacturing.Storage;

public sealed record ManufacturingBackupManifest(
    int FormatVersion,
    int DatabaseSchemaVersion,
    DateTimeOffset CreatedAt,
    string Workstation,
    string DatabaseEntry,
    string DatabaseSha256,
    bool ReportsIncluded);

public sealed record ManufacturingBackupPreview(
    string ArchivePath,
    ManufacturingBackupManifest Manifest,
    bool HashValid,
    bool IntegrityValid,
    bool SchemaSupported)
{
    public bool CanRestore => this.HashValid && this.IntegrityValid && this.SchemaSupported;
}

public sealed record ManufacturingRestoreResult(string DatabasePath, string PreRestoreBackupPath);

public sealed class ManufacturingBackupService(SqliteManufacturingStore store, TimeProvider? timeProvider = null)
{
    private const int BackupFormatVersion = 1;
    private const string DatabaseEntry = "manufacturing.db";
    private const string ManifestEntry = "backup-manifest.json";
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;

    public async Task<string> CreateAsync(string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationDirectory);
        var destination = Path.GetFullPath(destinationDirectory);
        Directory.CreateDirectory(destination);
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        var stamp = this.clock.GetUtcNow().ToString("yyyyMMdd-HHmmssfff",
            System.Globalization.CultureInfo.InvariantCulture);
        var finalPath = Path.Combine(destination, $"maxiems-manufacturing-{stamp}.mxbak");
        var temporaryArchive = finalPath + $".{Guid.NewGuid():N}.tmp";
        var temporaryDatabase = Path.Combine(Path.GetTempPath(), $"maxiems-backup-{Guid.NewGuid():N}.db");
        try
        {
            await CreateDatabaseSnapshotAsync(temporaryDatabase, cancellationToken).ConfigureAwait(false);
            var hash = await HashAsync(temporaryDatabase, cancellationToken).ConfigureAwait(false);
            var schema = await ReadSchemaVersionAsync(temporaryDatabase, cancellationToken).ConfigureAwait(false);
            var manifest = new ManufacturingBackupManifest(BackupFormatVersion, schema, this.clock.GetUtcNow(),
                Environment.MachineName, DatabaseEntry, hash, false);
            await using (var output = new FileStream(temporaryArchive, FileMode.CreateNew, FileAccess.ReadWrite,
                FileShare.None, 81920, FileOptions.Asynchronous | FileOptions.WriteThrough))
            using (var archive = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: false))
            {
                archive.CreateEntryFromFile(temporaryDatabase, DatabaseEntry, CompressionLevel.Optimal);
                var entry = archive.CreateEntry(ManifestEntry, CompressionLevel.Optimal);
                await using var stream = entry.Open();
                await JsonSerializer.SerializeAsync(stream, manifest, cancellationToken: cancellationToken)
                    .ConfigureAwait(false);
            }
            File.Move(temporaryArchive, finalPath, false);
            return finalPath;
        }
        catch
        {
            if (File.Exists(temporaryArchive))
                File.Delete(temporaryArchive);
            throw;
        }
        finally
        {
            if (File.Exists(temporaryDatabase))
                File.Delete(temporaryDatabase);
        }
    }

    public async Task<ManufacturingBackupPreview> PreviewAsync(string archivePath,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(archivePath);
        var fullPath = Path.GetFullPath(archivePath);
        if (fullPath.Equals(store.DatabasePath, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("A database file is not a manufacturing backup archive.");
        }
        var temporary = Path.Combine(Path.GetTempPath(), $"maxiems-preview-{Guid.NewGuid():N}.db");
        try
        {
            using var archive = ZipFile.OpenRead(fullPath);
            if (archive.Entries.Count > 2 || archive.Entries.Any(entry => entry.FullName.Contains("..",
                StringComparison.Ordinal) || Path.IsPathRooted(entry.FullName)))
            {
                throw new InvalidDataException("Backup contains an invalid entry layout.");
            }
            var manifestEntry = archive.GetEntry(ManifestEntry)
                ?? throw new InvalidDataException("Backup manifest is missing.");
            ManufacturingBackupManifest manifest;
            await using (var stream = manifestEntry.Open())
            {
                manifest = await JsonSerializer.DeserializeAsync<ManufacturingBackupManifest>(stream,
                    cancellationToken: cancellationToken).ConfigureAwait(false)
                    ?? throw new InvalidDataException("Backup manifest is invalid.");
            }
            if (manifest.FormatVersion != BackupFormatVersion || manifest.DatabaseEntry != DatabaseEntry)
            {
                throw new InvalidDataException("Backup format is unsupported.");
            }
            var database = archive.GetEntry(DatabaseEntry)
                ?? throw new InvalidDataException("Backup database is missing.");
            await using (var source = database.Open())
            await using (var destination = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write,
                FileShare.None, 81920, FileOptions.Asynchronous))
            {
                await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
            }
            var hashValid = (await HashAsync(temporary, cancellationToken).ConfigureAwait(false))
                .Equals(manifest.DatabaseSha256, StringComparison.Ordinal);
            var integrity = hashValid && await IntegrityValidAsync(temporary, cancellationToken).ConfigureAwait(false);
            var supported = manifest.DatabaseSchemaVersion <= ManufacturingMigrations.All.Max(item => item.Version);
            return new(fullPath, manifest, hashValid, integrity, supported);
        }
        finally
        {
            if (File.Exists(temporary))
                File.Delete(temporary);
        }
    }

    public async Task<ManufacturingRestoreResult> RestoreAsync(string archivePath,
        CancellationToken cancellationToken = default)
    {
        var preview = await this.PreviewAsync(archivePath, cancellationToken).ConfigureAwait(false);
        if (!preview.CanRestore)
            throw new InvalidDataException("Backup did not pass restore validation.");
        await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
        var stamp = this.clock.GetUtcNow().ToString("yyyyMMdd-HHmmssfff",
            System.Globalization.CultureInfo.InvariantCulture);
        var preRestore = store.DatabasePath + $".pre-restore-{stamp}.bak";
        var replacement = store.DatabasePath + $".{Guid.NewGuid():N}.restore";
        await CreateDatabaseSnapshotAsync(preRestore, cancellationToken).ConfigureAwait(false);
        try
        {
            using (var archive = ZipFile.OpenRead(preview.ArchivePath))
            {
                archive.GetEntry(DatabaseEntry)!.ExtractToFile(replacement, false);
            }
            if (!await IntegrityValidAsync(replacement, cancellationToken).ConfigureAwait(false))
            {
                throw new InvalidDataException("Restored database failed integrity validation.");
            }
            File.Move(replacement, store.DatabasePath, true);
            foreach (var suffix in new[] { "-wal", "-shm" })
            {
                if (File.Exists(store.DatabasePath + suffix))
                    File.Delete(store.DatabasePath + suffix);
            }
            await store.InitializeAsync(cancellationToken).ConfigureAwait(false);
            return new(store.DatabasePath, preRestore);
        }
        catch
        {
            if (File.Exists(replacement))
                File.Delete(replacement);
            File.Copy(preRestore, store.DatabasePath, true);
            throw;
        }
    }

    private async Task CreateDatabaseSnapshotAsync(string path, CancellationToken cancellationToken)
    {
        await using var source = await store.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var destination = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Pooling = false,
        }.ToString());
        await destination.OpenAsync(cancellationToken).ConfigureAwait(false);
        source.BackupDatabase(destination);
    }

    private static async Task<int> ReadSchemaVersionAsync(string path, CancellationToken cancellationToken)
    {
        await using var connection = await OpenReadOnlyAsync(path, cancellationToken).ConfigureAwait(false);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COALESCE(MAX(version), 0) FROM schema_migrations";
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false),
            System.Globalization.CultureInfo.InvariantCulture);
    }

    private static async Task<bool> IntegrityValidAsync(string path, CancellationToken cancellationToken)
    {
        try
        {
            await using var connection = await OpenReadOnlyAsync(path, cancellationToken).ConfigureAwait(false);
            await using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA integrity_check;";
            return string.Equals(Convert.ToString(await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false),
                System.Globalization.CultureInfo.InvariantCulture), "ok", StringComparison.OrdinalIgnoreCase);
        }
        catch (SqliteException)
        {
            return false;
        }
    }

    private static async Task<SqliteConnection> OpenReadOnlyAsync(string path, CancellationToken cancellationToken)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadOnly,
            Pooling = false,
        }.ToString());
        await connection.OpenAsync(cancellationToken).ConfigureAwait(false);
        return connection;
    }

    private static async Task<string> HashAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false))
            .ToLowerInvariant();
    }
}
